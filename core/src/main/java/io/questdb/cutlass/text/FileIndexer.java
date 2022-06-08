/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cutlass.text;

import io.questdb.MessageBus;
import io.questdb.cairo.*;
import io.questdb.cairo.sql.RecordMetadata;
import io.questdb.cairo.vm.Vm;
import io.questdb.cairo.vm.api.MemoryCMARW;
import io.questdb.cairo.vm.api.MemoryMARW;
import io.questdb.cutlass.text.types.*;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.griffin.engine.functions.columns.ColumnUtils;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.log.LogRecord;
import io.questdb.mp.RingQueue;
import io.questdb.mp.SOUnboundedCountDownLatch;
import io.questdb.mp.Sequence;
import io.questdb.std.*;
import io.questdb.std.datetime.DateFormat;
import io.questdb.std.datetime.DateLocale;
import io.questdb.std.str.DirectByteCharSequence;
import io.questdb.std.str.DirectCharSink;
import io.questdb.std.str.Path;
import io.questdb.std.str.StringSink;
import org.jetbrains.annotations.TestOnly;

import java.io.Closeable;
import java.io.IOException;


/**
 * Class is responsible for pre-processing of large unordered import files meant to go into partitioned tables.
 * It does the following (in parallel) :
 * - splits the file into N-chunks, scans in parallel and finds correct line start for each chunk
 * - scans each chunk and extract timestamps and line offsets to per-partition index files
 * (index files are stored as $inputWorkDir/$inputFileName/$partitionName/$workerId_$chunkNumber)
 * then it sorts each file by timestamp value
 * - merges all partition index chunks into one index file per partition
 * - loads partitions into separate tables using merged indexes (one table per worker)
 * - move partitions from temp tables and attaches them to final table
 * - removes temp tables and index files
 * <p>
 */
public class FileIndexer implements Closeable, Mutable {

    private static final Log LOG = LogFactory.getLog(FileIndexer.class);

    private static final String LOCK_REASON = "parallel import";
    private static final int NO_INDEX = -1;

    private static final int DEFAULT_MIN_CHUNK_SIZE = 300 * 1024 * 1024;
    private int minChunkSize = DEFAULT_MIN_CHUNK_SIZE;

    //holds result of first phase - boundary scanning
    //count of quotes, even new lines, odd new lines, offset to first even newline, offset to first odd newline
    private final LongList chunkStats = new LongList();

    //holds input for second phase - indexing: offset and start line number for each chunk
    private final LongList indexChunkStats = new LongList();
    //stats calculated during indexing phase, maxLineLength for each worker 
    private final LongList indexStats = new LongList();

    private final ObjList<TaskContext> contextObjList = new ObjList<>();

    private final FilesFacade ff;

    private final Path inputFilePath = new Path();
    private final int dirMode;
    private final Path tmpPath = new Path();

    private final RingQueue<TextImportTask> queue;
    private final Sequence pubSeq;
    private final Sequence subSeq;
    private final int workerCount;
    private final SOUnboundedCountDownLatch doneLatch = new SOUnboundedCountDownLatch();

    private final CharSequence inputRoot;
    private final CharSequence inputWorkRoot;
    //path to import directory under, usually $inputWorkRoot/$tableName
    private CharSequence importRoot;

    //input params start
    private CharSequence tableName;
    //name of file to process in inputRoot dir
    private CharSequence inputFileName;
    //name of timestamp column
    private CharSequence timestampColumn;
    private int partitionBy;
    private byte columnDelimiter;
    private TimestampAdapter timestampAdapter;
    private final ObjectPool<OtherToTimestampAdapter> otherToTimestampAdapterPool = new ObjectPool<>(OtherToTimestampAdapter::new, 4);
    private boolean forceHeader;
    //input params end
    //index of timestamp column in input file
    private int timestampIndex;
    private int maxLineLength;
    private final CairoSecurityContext securityContext;

    private final ObjList<LongList> partitionKeys = new ObjList<>();
    private final StringSink partitionNameSink = new StringSink();
    private final ObjList<CharSequence> partitionNames = new ObjList<>();
    private final IntList taskDistribution = new IntList();

    private final DateLocale defaultDateLocale;
    private final DirectCharSink utf8Sink;
    private final TypeManager typeManager;
    private final TextDelimiterScanner textDelimiterScanner;
    private final TextMetadataDetector textMetadataDetector;

    private final SqlExecutionContext sqlExecutionContext;
    private final CairoEngine cairoEngine;
    private final CairoConfiguration configuration;

    private final TableStructureAdapter targetTableStructure;

    public FileIndexer(SqlExecutionContext sqlExecutionContext) {
        this.sqlExecutionContext = sqlExecutionContext;
        this.cairoEngine = sqlExecutionContext.getCairoEngine();
        this.securityContext = sqlExecutionContext.getCairoSecurityContext();
        this.configuration = cairoEngine.getConfiguration();

        MessageBus bus = sqlExecutionContext.getMessageBus();
        this.queue = bus.getTextImportQueue();
        this.pubSeq = bus.getTextImportPubSeq();
        this.subSeq = bus.getTextImportSubSeq();

        CairoConfiguration cfg = sqlExecutionContext.getCairoEngine().getConfiguration();
        this.workerCount = sqlExecutionContext.getWorkerCount();

        this.ff = cfg.getFilesFacade();

        this.inputRoot = cfg.getInputRoot();
        this.inputWorkRoot = cfg.getInputWorkRoot();
        this.dirMode = cfg.getMkDirMode();

        TextConfiguration textConfiguration = configuration.getTextConfiguration();
        this.utf8Sink = new DirectCharSink(textConfiguration.getUtf8SinkSize());
        this.typeManager = new TypeManager(textConfiguration, utf8Sink);
        this.textDelimiterScanner = new TextDelimiterScanner(textConfiguration);
        this.textMetadataDetector = new TextMetadataDetector(typeManager, textConfiguration);
        this.defaultDateLocale = textConfiguration.getDefaultDateLocale();

        this.targetTableStructure = new TableStructureAdapter(configuration);

        for (int i = 0; i < workerCount; i++) {
            contextObjList.add(new TaskContext(cairoEngine));
            partitionKeys.add(new LongList());
        }
    }

    public static void createTable(final FilesFacade ff, int mkDirMode, final CharSequence root, final CharSequence tableName, TableStructure structure, int tableId) {
        try (Path path = new Path()) {
            switch (TableUtils.exists(ff, path, root, tableName, 0, tableName.length())) {
                case TableUtils.TABLE_EXISTS:
                    int errno;
                    if ((errno = ff.rmdir(path)) != 0) {
                        LOG.error().$("remove failed [tableName='").utf8(tableName).$("', error=").$(errno).$(']').$();
                        throw CairoException.instance(errno).put("Table remove failed");
                    }
                case TableUtils.TABLE_DOES_NOT_EXIST:
                    try (MemoryMARW memory = Vm.getMARWInstance()) {
                        TableUtils.createTable(
                                ff,
                                root,
                                mkDirMode,
                                memory,
                                path,
                                tableName,
                                structure,
                                ColumnType.VERSION,
                                tableId
                        );
                    }
                    break;
                default:
                    throw CairoException.instance(0).put("name is reserved [tableName=").put(tableName).put(']');
            }
        }
    }

    @Override
    public void close() {
        clear();
        Misc.freeObjList(contextObjList);
        this.inputFilePath.close();
        this.tmpPath.close();
        this.utf8Sink.close();
        this.textMetadataDetector.close();
        this.textDelimiterScanner.close();
    }

    public static void mergeColumnSymbolTables(final CairoConfiguration cfg,
                                               final CharSequence importRoot,
                                               final TableWriter writer,
                                               final CharSequence table,
                                               final CharSequence column,
                                               int columnIndex,
                                               int symbolColumnIndex,
                                               int tmpTableCount,
                                               int partitionBy
    ) {
        final FilesFacade ff = cfg.getFilesFacade();
        try (Path path = new Path()) {
            path.of(importRoot).concat(table);
            int plen = path.length();
            for (int i = 0; i < tmpTableCount; i++) {
                path.trimTo(plen);
                path.put("_").put(i);
                try (TxReader txFile = new TxReader(ff).ofRO(path, partitionBy)) {
                    txFile.unsafeLoadAll();
                    int symbolCount = txFile.getSymbolValueCount(symbolColumnIndex);
                    try (SymbolMapReaderImpl reader = new SymbolMapReaderImpl(cfg, path, column, TableUtils.COLUMN_NAME_TXN_NONE, symbolCount)) {
                        try (MemoryCMARW mem = Vm.getSmallCMARWInstance(
                                ff,
                                path.concat(column).put(TableUtils.SYMBOL_KEY_REMAP_FILE_SUFFIX).$(),
                                MemoryTag.MMAP_DEFAULT,
                                cfg.getWriterFileOpenOpts()
                        )
                        ) {
                            SymbolMapWriter.mergeSymbols(writer.getSymbolMapWriter(columnIndex), reader, mem);
                        }
                    }
                }
            }
        }
    }

    public IntList importPartitions() throws TextException {
        if (partitionNames.size() == 0) {
            throw TextException.$("No partitions to merge and load found");
        }

        LOG.info().$("Started index merge and partition load").$();

        final int partitionCount = partitionNames.size();
        final int chunkSize = (partitionCount + workerCount - 1) / workerCount;
        final int taskCount = (partitionCount + chunkSize - 1) / chunkSize;

        int queuedCount = 0;
        doneLatch.reset();
        taskDistribution.clear();
        for (int i = 0; i < taskCount; ++i) {
            final TaskContext context = contextObjList.get(i);
            final int lo = i * chunkSize;
            final int hi = Integer.min(lo + chunkSize, partitionCount);
            final long seq = pubSeq.next();
            if (seq < 0) {
                context.importPartitionStage(i, lo, hi, partitionNames, maxLineLength);
            } else {
                queue.get(seq).of(doneLatch, TextImportTask.PHASE_PARTITION_IMPORT, context, i, lo, hi, partitionNames, maxLineLength);
                pubSeq.done(seq);
                queuedCount++;
            }
            taskDistribution.add(i);
            taskDistribution.add(lo);
            taskDistribution.add(hi);
        }

        waitForWorkers(queuedCount);

        LOG.info().$("Finished index merge and partition load").$();
        return taskDistribution;
    }

    public void parallelMergeSymbolTables(final int tmpTableCount, final TableWriter writer) {
        LOG.info().$("Started symbol table merge").$();

        int queuedCount = 0;
        doneLatch.reset();
        TableWriterMetadata metadata = writer.getMetadata();
        int symbolColumnIndex = -1;
        for (int c = 0, size = metadata.getColumnCount(); c < size; c++) {
            if (ColumnType.isSymbol(metadata.getColumnType(c))) {
                symbolColumnIndex++;
                final CharSequence symbolColumnName = metadata.getColumnName(c);
                final long seq = pubSeq.next();
                if (seq < 0) {
                    FileIndexer.mergeColumnSymbolTables(configuration, importRoot, writer, tableName, symbolColumnName, c, symbolColumnIndex, tmpTableCount, partitionBy);
                } else {
                    queue.get(seq).of(doneLatch,
                            TextImportTask.PHASE_SYMBOL_TABLE_MERGE,
                            importRoot,
                            configuration,
                            writer,
                            tableName,
                            symbolColumnName,
                            c,
                            symbolColumnIndex,
                            tmpTableCount,
                            partitionBy);
                    pubSeq.done(seq);
                    queuedCount++;
                }
            }
        }

        waitForWorkers(queuedCount);
        LOG.info().$("Finished symbol table merge").$();
    }

    public void parallelUpdateSymbolKeys(int tmpTableCount, final TableWriter writer) {
        LOG.info().$("Started symbol keys update").$();

        int queuedCount = 0;
        doneLatch.reset();
        for (int t = 0; t < tmpTableCount; ++t) {
            final TaskContext context = contextObjList.get(t);
            tmpPath.of(importRoot).concat(tableName).put("_").put(t);
            try (TxReader txFile = new TxReader(ff).ofRO(tmpPath, partitionBy)) {
                txFile.unsafeLoadAll();
                final int partitionCount = txFile.getPartitionCount();
                for (int p = 0; p < partitionCount; p++) {
                    final long partitionSize = txFile.getPartitionSize(p);
                    final long partitionTimestamp = txFile.getPartitionTimestamp(p);
                    TableWriterMetadata metadata = writer.getMetadata();
                    int symbolColumnIndex = 0;
                    for (int c = 0, size = metadata.getColumnCount(); c < size; c++) {
                        if (ColumnType.isSymbol(metadata.getColumnType(c))) {
                            final CharSequence symbolColumnName = metadata.getColumnName(c);
                            final int symbolCount = txFile.getSymbolValueCount(symbolColumnIndex++);
                            final long seq = pubSeq.next();
                            if (seq < 0) {
                                context.updateSymbolKeys(t, partitionSize, partitionTimestamp, symbolColumnName, symbolCount);
                            } else {
                                queue.get(seq).of(doneLatch,
                                        TextImportTask.PHASE_UPDATE_SYMBOL_KEYS,
                                        context,
                                        t,
                                        partitionSize,
                                        partitionTimestamp,
                                        symbolColumnName,
                                        symbolCount);
                                pubSeq.done(seq);
                                queuedCount++;
                            }
                        }
                    }
                }
            }
        }

        waitForWorkers(queuedCount);
        LOG.info().$("Finished symbol keys update").$();
    }

    public void of(CharSequence tableName, CharSequence inputFileName, int partitionBy, byte columnDelimiter, CharSequence timestampColumn, CharSequence tsFormat, boolean forceHeader) {
        clear();

        this.tableName = tableName;
        this.importRoot = tmpPath.of(inputWorkRoot).concat(tableName).toString();
        this.inputFileName = inputFileName;
        this.timestampColumn = timestampColumn;
        this.partitionBy = partitionBy;
        this.columnDelimiter = columnDelimiter;
        if (tsFormat != null) {
            DateFormat dateFormat = typeManager.getInputFormatConfiguration().getTimestampFormatFactory().get(tsFormat);
            this.timestampAdapter = (TimestampAdapter) typeManager.nextTimestampAdapter(false, dateFormat, defaultDateLocale);
        }
        this.forceHeader = forceHeader;
        this.timestampIndex = -1;

        inputFilePath.of(inputRoot).slash().concat(inputFileName).$();
    }

    @TestOnly
    void setBufferLength(int bufferSize) {
        for (int i = 0; i < contextObjList.size(); i++) {
            TaskContext context = contextObjList.get(i);
            context.splitter.setBufferLength(bufferSize);
        }
    }

    @Override
    public void clear() {
        doneLatch.reset();
        chunkStats.clear();
        indexChunkStats.clear();
        indexStats.clear();
        partitionNames.clear();
        partitionNameSink.clear();
        utf8Sink.clear();
        typeManager.clear();
        textMetadataDetector.clear();
        otherToTimestampAdapterPool.clear();

        inputFileName = null;
        tableName = null;
        timestampColumn = null;
        timestampIndex = -1;
        partitionBy = -1;
        columnDelimiter = -1;
        timestampAdapter = null;
        forceHeader = false;
        maxLineLength = 0;

        for (int i = 0; i < contextObjList.size(); i++) {
            contextObjList.get(i).clear();
        }

        for (int i = 0; i < partitionKeys.size(); i++) {
            partitionKeys.get(i).clear();
        }
    }

    private void removeWorkDir() {
        Path workDirPath = tmpPath.of(importRoot).slash$();

        if (ff.exists(workDirPath)) {
            LOG.info().$("removing import directory path='").$(workDirPath).$("'").$();

            int errno = ff.rmdir(workDirPath);
            if (errno != 0) {
                throw CairoException.instance(errno).put("Can't remove import directory path='").put(workDirPath).put("' errno=").put(errno);
            }
        }
    }

    private void createWorkDir() {
        removeWorkDir();

        Path workDirPath = tmpPath.of(importRoot).slash$();
        int errno = ff.mkdir(workDirPath, dirMode);
        if (errno != 0) {
            throw CairoException.instance(errno).put("Can't create import work dir ").put(workDirPath).put(" errno=").put(errno);
        }

        LOG.info().$("created import dir ").$(workDirPath).$();
    }

    public void parseStructure() throws TextException {
        final CairoConfiguration configuration = sqlExecutionContext.getCairoEngine().getConfiguration();

        final int textAnalysisMaxLines = configuration.getTextConfiguration().getTextAnalysisMaxLines();
        int len = configuration.getSqlCopyBufferSize();
        long buf = Unsafe.malloc(len, MemoryTag.NATIVE_DEFAULT);
        long fd = -1;

        try (TextLexer lexer = new TextLexer(configuration.getTextConfiguration(), typeManager)) {
            tmpPath.of(inputRoot).concat(inputFileName).$();
            fd = ff.openRO(tmpPath);

            if (fd == -1) {
                throw TextException.$("could not open file [errno=").put(Os.errno()).put(", path=").put(tmpPath).put(']');
            }
            if (ff.length(fd) < 1) {
                throw TextException.$("Ignoring file because it's empty. Path=").put(inputFilePath);
            }

            long n = ff.read(fd, buf, len, 0);
            if (n > 0) {
                if (columnDelimiter < 0) {
                    columnDelimiter = textDelimiterScanner.scan(buf, buf + n);
                }

                lexer.of(columnDelimiter);
                lexer.setSkipLinesWithExtraValues(false);

                final ObjList<CharSequence> names = new ObjList<>();
                final ObjList<TypeAdapter> types = new ObjList<>();
                if (timestampColumn != null && timestampAdapter != null) {
                    names.add(timestampColumn);
                    types.add(timestampAdapter);
                }

                textMetadataDetector.of(names, types, forceHeader);
                lexer.parse(buf, buf + n, textAnalysisMaxLines, textMetadataDetector);
                textMetadataDetector.evaluateResults(lexer.getLineCount(), lexer.getErrorCount());
                forceHeader = textMetadataDetector.isHeader();

                prepareTable(securityContext, textMetadataDetector.getColumnNames(), textMetadataDetector.getColumnTypes(), tmpPath, typeManager);
                prepareContexts();
            }
        } finally {
            if (fd != -1) {
                ff.close(fd);
            }
            Unsafe.free(buf, len, MemoryTag.NATIVE_DEFAULT);
        }
    }

    private void parallelBuildColumnIndexes(int tmpTableCount, TableWriter writer) {
        final RecordMetadata metadata = writer.getMetadata();
        final int columnCount = metadata.getColumnCount();

        boolean isAnyIndexed = false;
        for (int i = 0; i < columnCount; i++) {
            isAnyIndexed |= metadata.isColumnIndexed(i);
        }

        if (isAnyIndexed) {
            LOG.info().$("Started build column indexes").$();

            int queuedCount = 0;
            doneLatch.reset();
            for (int t = 0; t < tmpTableCount; ++t) {
                final TaskContext context = contextObjList.get(t);
                final long seq = pubSeq.next();
                if (seq < 0) {
                    context.buildColumnIndexesStage(t, metadata);
                } else {
                    queue.get(seq).of(doneLatch, TextImportTask.PHASE_BUILD_INDEX, context, t, metadata);
                    pubSeq.done(seq);
                    queuedCount++;
                }
            }
            waitForWorkers(queuedCount);
            LOG.info().$("Finished build column indexes").$();
        }
    }

    private void movePartitionsToDst(IntList taskDistribution, int taskCount) {
        for (int i = 0; i < taskCount; i++) {
            int index = taskDistribution.getQuick(i * 3);
            int lo = taskDistribution.getQuick(i * 3 + 1);
            int hi = taskDistribution.getQuick(i * 3 + 2);
            final Path srcPath = Path.getThreadLocal(importRoot).concat(tableName).put("_").put(index);
            final Path dstPath = Path.getThreadLocal2(configuration.getRoot()).concat(tableName);
            int srcPlen = srcPath.length();
            int dstPlen = dstPath.length();
            for (int j = lo; j < hi; j++) {
                final CharSequence partitionName = partitionNames.get(j);
                srcPath.trimTo(srcPlen);
                srcPath.concat(partitionName).$();
                dstPath.trimTo(dstPlen);
                dstPath.concat(partitionName).$();
                if (!ff.rename(srcPath, dstPath)) {
                    LOG.error().$("Can't move ").$(srcPath).$(" to ").$(dstPath).$(" errno=").$(ff.errno()).$();
                }
            }
        }
    }

    public void process() throws SqlException, TextException {
        long fd = ff.openRO(inputFilePath);
        if (fd < 0) {
            throw TextException.$("Can't open input file=").put(inputFilePath).put(", errno=").put(ff.errno());
        }

        try (TableWriter writer = cairoEngine.getWriter(sqlExecutionContext.getCairoSecurityContext(), tableName, LOCK_REASON)) {
            findChunkBoundaries(fd);
            indexChunks();
            IntList taskDistribution = importPartitions();
            int taskCount = taskDistribution.size() / 3;
            parallelMergeSymbolTables(taskCount, writer);
            parallelUpdateSymbolKeys(taskCount, writer);
            parallelBuildColumnIndexes(taskCount, writer);
            movePartitionsToDst(taskDistribution, taskCount);
            attachPartitions(writer);
        } finally {
            removeWorkDir();
            ff.close(fd);
        }
    }

    private void attachPartitions(TableWriter writer) throws TextException {
        if (partitionNames.size() == 0) {
            throw TextException.$("No partitions to attach found");
        }

        LOG.info().$("Started attaching partitions").$();

        for (int i = 0, sz = partitionNames.size(); i < sz; i++) {
            final CharSequence partitionDirName = partitionNames.get(i);
            try {
                final long timestamp = PartitionBy.parsePartitionDirName(partitionDirName, partitionBy);
                writer.attachPartition(timestamp, true); //TODO: change to false to speed up attaching
            } catch (CairoException e) {
                LOG.error().$("Cannot parse partition directory name=").$(partitionDirName).$((Throwable) e).$();
            }
        }

        LOG.info().$("Finished attaching partitions").$();
    }

    public void setMinChunkSize(int minChunkSize) {
        this.minChunkSize = minChunkSize;
    }

    //returns list with N chunk boundaries
    LongList findChunkBoundaries(long fd) throws TextException {
        LOG.info().$("Started checking boundaries in file=").$(inputFilePath).$();

        final long fileLength = ff.length(fd);

        assert (workerCount > 0 && minChunkSize > 0);

        if (workerCount == 1) {
            indexChunkStats.setPos(0);
            indexChunkStats.add(0);
            indexChunkStats.add(0);
            indexChunkStats.add(fileLength);
            indexChunkStats.add(0);
            return indexChunkStats;
        }

        long chunkSize = fileLength / workerCount;
        chunkSize = Math.max(minChunkSize, chunkSize);
        final int chunks = (int) Math.max(fileLength / chunkSize, 1);

        int queuedCount = 0;
        doneLatch.reset();

        chunkStats.setPos(chunks * 5);
        chunkStats.zero(0);

        for (int i = 0; i < chunks; i++) {
            TaskContext context = contextObjList.get(i);
            final long chunkLo = i * chunkSize;
            final long chunkHi = Long.min(chunkLo + chunkSize, fileLength);

            final long seq = pubSeq.next();
            if (seq < 0) {
                context.countQuotesStage(5 * i, chunkLo, chunkHi, chunkStats);
            } else {
                queue.get(seq).of(doneLatch, TextImportTask.PHASE_BOUNDARY_CHECK, context, 5 * i, chunkLo, chunkHi, -1, chunkStats, null);
                pubSeq.done(seq);
                queuedCount++;
            }
        }

        waitForWorkers(queuedCount);
        processChunkStats(fileLength, chunks);

        LOG.info().$("Finished checking boundaries in file=").$(inputFilePath).$();

        return indexChunkStats;
    }

    void indexChunks() throws SqlException, TextException {
        int queuedCount = 0;
        doneLatch.reset();

        if (indexChunkStats.size() < 2) {
            throw TextException.$("No chunks found for indexing in file=").put(inputFilePath);
        }

        LOG.info().$("Started indexing file=").$(inputFilePath).$();
        createWorkDir();
        indexStats.setPos((indexChunkStats.size() - 2) / 2);
        indexStats.zero(0);

        for (int i = 0, n = indexChunkStats.size() - 2; i < n; i += 2) {
            int colIdx = i / 2;

            TaskContext context = contextObjList.get(colIdx);
            final long chunkLo = indexChunkStats.get(i);
            final long lineNumber = indexChunkStats.get(i + 1);
            final long chunkHi = indexChunkStats.get(i + 2);

            final long seq = pubSeq.next();
            if (seq < 0) {
                context.buildIndexStage(chunkLo, chunkHi, lineNumber, indexStats, colIdx, partitionKeys.get(colIdx));
            } else {
                queue.get(seq).of(doneLatch, TextImportTask.PHASE_INDEXING, context, colIdx, chunkLo, chunkHi, lineNumber, indexStats, partitionKeys.get(colIdx));
                pubSeq.done(seq);
                queuedCount++;
            }
        }

        // process our own queue (this should fix deadlock with 1 worker configuration)
        waitForWorkers(queuedCount);
        processIndexStats(partitionKeys);

        LOG.info().$("Finished indexing file=").$(inputFilePath).$();
    }

    private void processIndexStats(ObjList<LongList> partitionKeys) {
        maxLineLength = 0;
        for (int i = 0, n = indexStats.size(); i < n; i++) {
            maxLineLength = (int) Math.max(maxLineLength, indexStats.get(i));
        }

        LongHashSet set = new LongHashSet();
        for (int i = 0, n = partitionKeys.size(); i < n; i++) {
            LongList keys = partitionKeys.get(i);
            for (int j = 0, m = keys.size(); j < m; j++) {
                set.add(keys.get(j));
            }
        }

        LongList uniquePartitionKeys = new LongList();
        for (int i = 0, n = set.size(); i < n; i++) {
            uniquePartitionKeys.add(set.get(i));
        }
        uniquePartitionKeys.sort();

        DateFormat dirFormat = PartitionBy.getPartitionDirFormatMethod(partitionBy);

        partitionNames.clear();
        tmpPath.of(importRoot).slash$();
        for (int i = 0, n = uniquePartitionKeys.size(); i < n; i++) {
            partitionNameSink.clear();
            dirFormat.format(uniquePartitionKeys.get(i), null, null, partitionNameSink);
            partitionNames.add(partitionNameSink.toString());
        }
    }

    private void processChunkStats(long fileLength, int chunks) {
        long quotes = chunkStats.get(0);

        indexChunkStats.setPos(0);
        //set first chunk offset and line number
        indexChunkStats.add(0);
        indexChunkStats.add(0);

        long lines;
        long totalLines = chunks > 0 ? chunkStats.get(1) + 1 : 1;

        for (int i = 1; i < chunks; i++) {
            long startPos;
            if ((quotes & 1) == 1) { // if number of quotes is odd then use odd starter
                startPos = chunkStats.get(5 * i + 4);
                lines = chunkStats.get(5 * i + 2);
            } else {
                startPos = chunkStats.get(5 * i + 3);
                lines = chunkStats.get(5 * i + 1);
            }

            //if whole chunk  belongs to huge quoted string or contains one very long line
            //then it should be ignored here and merged with previous chunk
            if (startPos > -1) {
                indexChunkStats.add(startPos);
                indexChunkStats.add(totalLines);
            }

            quotes += chunkStats.get(5 * i);
            totalLines += lines;
        }

        if (indexChunkStats.get(indexChunkStats.size() - 2) < fileLength) {
            indexChunkStats.add(fileLength);
            indexChunkStats.add(totalLines);//doesn't matter
        }
    }

    // process our own queue (this should fix deadlock with 1 worker configuration)
    private void waitForWorkers(int queuedCount) {
        // process our own queue (this should fix deadlock with 1 worker configuration)
        while (doneLatch.getCount() > -queuedCount) {
            long seq = subSeq.next();
            if (seq > -1) {
                queue.get(seq).run();
                subSeq.done(seq);
            }
        }

        doneLatch.await(queuedCount);
        doneLatch.reset();
    }

    @TestOnly
    int getMaxLineLength() {
        return maxLineLength;
    }

    private void logTypeError(int i, int type) {
        LOG.info()
                .$("mis-detected [table=").$(tableName)
                .$(", column=").$(i)
                .$(", type=").$(ColumnType.nameOf(type))
                .$(']').$();
    }

    private TableWriter openWriterAndOverrideImportMetadata(
            ObjList<CharSequence> names,
            ObjList<TypeAdapter> types,
            CairoSecurityContext cairoSecurityContext,
            TypeManager typeManager
    ) throws TextException {
        TableWriter writer = cairoEngine.getWriter(cairoSecurityContext, tableName, LOCK_REASON);
        RecordMetadata metadata = writer.getMetadata();

        if (metadata.getColumnCount() < types.size()) {
            writer.close();
            throw TextException.$("column count mismatch [textColumnCount=").put(types.size())
                    .put(", tableColumnCount=").put(metadata.getColumnCount())
                    .put(", table=").put(tableName)
                    .put(']');
        }

        //remap index is only needed to adjust names and types
        //workers will import data into temp tables without remapping
        IntList remapIndex = new IntList();
        remapIndex.ensureCapacity(types.size());
        for (int i = 0, n = types.size(); i < n; i++) {

            final int columnIndex = metadata.getColumnIndexQuiet(names.getQuick(i));
            final int idx = (columnIndex > -1 && columnIndex != i) ? columnIndex : i; // check for strict match ?
            remapIndex.set(i, idx);

            final int columnType = metadata.getColumnType(idx);
            final TypeAdapter detectedAdapter = types.getQuick(i);
            final int detectedType = detectedAdapter.getType();
            if (detectedType != columnType) {
                // when DATE type is mis-detected as STRING we
                // would not have either date format nor locale to
                // use when populating this field
                switch (ColumnType.tagOf(columnType)) {
                    case ColumnType.DATE:
                        logTypeError(i, detectedType);
                        types.setQuick(i, BadDateAdapter.INSTANCE);
                        break;
                    case ColumnType.TIMESTAMP:
                        if (detectedAdapter instanceof TimestampCompatibleAdapter) {
                            types.setQuick(i, otherToTimestampAdapterPool.next().of((TimestampCompatibleAdapter) detectedAdapter));
                        } else {
                            logTypeError(i, detectedType);
                            types.setQuick(i, BadTimestampAdapter.INSTANCE);
                        }
                        break;
                    case ColumnType.BINARY:
                        writer.close();
                        throw CairoException.instance(0).put("cannot import text into BINARY column [index=").put(i).put(']');
                    default:
                        types.setQuick(i, typeManager.getTypeAdapter(columnType));
                        break;
                }
            }
        }

        //at this point we've to use target table columns names otherwise partition attach could fail on metadata differences
        //(if header names or synthetic names are different from table's)
        for (int i = 0, n = remapIndex.size(); i < n; i++) {
            names.set(i, metadata.getColumnName(remapIndex.get(i)));
        }

        //add table columns missing in input file
        if (names.size() < metadata.getColumnCount()) {
            for (int i = 0, n = metadata.getColumnCount(); i < n; i++) {
                boolean unused = true;

                for (int r = 0, rn = remapIndex.size(); r < rn; r++) {
                    if (remapIndex.get(r) == i) {
                        unused = false;
                        break;
                    }
                }

                if (unused) {
                    names.add(metadata.getColumnName(i));
                    types.add(typeManager.getTypeAdapter(metadata.getColumnType(i)));
                }
            }
        }

        return writer;
    }

    void prepareContexts() {
        targetTableStructure.setIgnoreColumnIndexedFlag(true);
        boolean forceHeader = this.forceHeader;
        for (int i = 0; i < contextObjList.size(); i++) {
            TaskContext context = contextObjList.get(i);
            context.of(i, importRoot, inputFileName, targetTableStructure, textMetadataDetector.getColumnTypes(), forceHeader, columnDelimiter, Atomicity.SKIP_ALL);
            if (forceHeader) {
                forceHeader = false;//Assumption: only first splitter will process file with header
            }
        }
    }

    void prepareTable(
            CairoSecurityContext cairoSecurityContext,
            ObjList<CharSequence> names,
            ObjList<TypeAdapter> types,
            Path path,
            TypeManager typeManager
    ) throws TextException {

        if (types.size() == 0) {
            throw CairoException.instance(0).put("cannot determine text structure");
        }
        if (partitionBy == PartitionBy.NONE) {
            throw CairoException.instance(-1).put("partition by unit can't be NONE for parallel import");
        }
        TableWriter writer = null;
        if (partitionBy < 0) {
            partitionBy = PartitionBy.NONE;
        }

        if (timestampIndex == -1 && timestampColumn != null) {
            for (int i = 0, n = names.size(); i < n; i++) {
                if (Chars.equalsIgnoreCase(names.get(i), timestampColumn)) {
                    timestampIndex = i;
                    break;
                }
            }
        }

        try {
            switch (cairoEngine.getStatus(cairoSecurityContext, path, tableName)) {
                case TableUtils.TABLE_DOES_NOT_EXIST:
                    if (partitionBy == PartitionBy.NONE) {
                        throw TextException.$("partition by unit must be set when importing to new table");
                    }
                    if (timestampColumn == null) {
                        throw TextException.$("timestamp column must be set when importing to new table");
                    }
                    if (timestampIndex == -1) {
                        throw TextException.$("timestamp column '").put(timestampColumn).put("' not found in file header");
                    }

                    validate(names, types, null, NO_INDEX);
                    targetTableStructure.of(tableName, names, types, timestampIndex, partitionBy);
                    createTable(ff, dirMode, configuration.getRoot(), tableName, targetTableStructure, (int) cairoEngine.getNextTableId());
                    writer = cairoEngine.getWriter(cairoSecurityContext, tableName, LOCK_REASON);
                    partitionBy = writer.getPartitionBy();
                    break;
                case TableUtils.TABLE_EXISTS:
                    writer = openWriterAndOverrideImportMetadata(names, types, cairoSecurityContext, typeManager);

                    if (writer.getRowCount() > 0) {
                        throw CairoException.instance(0).put("target table must be empty [table=").put(tableName).put(']');
                    }

                    CharSequence designatedTimestampColumnName = writer.getDesignatedTimestampColumnName();
                    int designatedTimestampIndex = writer.getMetadata().getTimestampIndex();
                    if (PartitionBy.isPartitioned(partitionBy) && partitionBy != writer.getPartitionBy()) {
                        throw CairoException.instance(-1).put("declared partition by unit doesn't match table's");
                    }
                    partitionBy = writer.getPartitionBy();
                    if (!PartitionBy.isPartitioned(partitionBy)) {
                        throw CairoException.instance(-1).put("target table is not partitioned");
                    }
                    validate(names, types, designatedTimestampColumnName, designatedTimestampIndex);
                    targetTableStructure.of(tableName, names, types, timestampIndex, partitionBy);
                    break;
                default:
                    throw CairoException.instance(0).put("name is reserved [table=").put(tableName).put(']');
            }
        } finally {
            if (writer != null) {
                writer.close();
            }
        }

        if (timestampIndex == -1) {
            throw CairoException.instance(-1).put("timestamp column not found");
        }

        if (timestampAdapter == null && ColumnType.isTimestamp(types.getQuick(timestampIndex).getType())) {
            timestampAdapter = (TimestampAdapter) types.getQuick(timestampIndex);
        }
    }

    void validate(ObjList<CharSequence> names,
                  ObjList<TypeAdapter> types,
                  CharSequence designatedTimestampColumnName, int designatedTimestampIndex) throws TextException {
        if (timestampColumn == null && designatedTimestampColumnName == null) {
            timestampIndex = NO_INDEX;
        } else if (timestampColumn != null) {
            timestampIndex = names.indexOf(timestampColumn);
            if (timestampIndex == NO_INDEX) {
                throw TextException.$("invalid timestamp column '").put(timestampColumn).put('\'');
            }
        } else {
            timestampIndex = names.indexOf(designatedTimestampColumnName);
            if (timestampIndex == NO_INDEX) {
                // columns in the imported file may not have headers, then use writer timestamp index
                timestampIndex = designatedTimestampIndex;
            }
        }

        if (timestampIndex != NO_INDEX) {
            final TypeAdapter timestampAdapter = types.getQuick(timestampIndex);
            final int typeTag = ColumnType.tagOf(timestampAdapter.getType());
            if ((typeTag != ColumnType.LONG && typeTag != ColumnType.TIMESTAMP) || timestampAdapter == BadTimestampAdapter.INSTANCE) {
                throw TextException.$("column no=").put(timestampIndex).put(", name='").put(timestampColumn).put("' is not a timestamp");
            }
        }
    }

    public static class TableStructureAdapter implements TableStructure {
        private final CairoConfiguration configuration;
        private final LongList columnBits = new LongList();
        private CharSequence tableName;
        private ObjList<CharSequence> columnNames;
        private int timestampColumnIndex;
        private int partitionBy;
        private boolean ignoreColumnIndexedFlag;

        public TableStructureAdapter(CairoConfiguration configuration) {
            this.configuration = configuration;
        }

        @Override
        public int getColumnCount() {
            return columnNames.size();
        }

        @Override
        public CharSequence getColumnName(int columnIndex) {
            return columnNames.getQuick(columnIndex);
        }

        @Override
        public int getColumnType(int columnIndex) {
            return Numbers.decodeLowInt(columnBits.getQuick(columnIndex));
        }

        @Override
        public long getColumnHash(int columnIndex) {
            return configuration.getRandom().nextLong();
        }

        @Override
        public int getIndexBlockCapacity(int columnIndex) {
            return configuration.getIndexValueBlockSize();
        }

        @Override
        public boolean isIndexed(int columnIndex) {
            return !ignoreColumnIndexedFlag && Numbers.decodeHighInt(columnBits.getQuick(columnIndex)) != 0;
        }

        @Override
        public boolean isSequential(int columnIndex) {
            return false;
        }

        @Override
        public int getPartitionBy() {
            return partitionBy;
        }

        @Override
        public boolean getSymbolCacheFlag(int columnIndex) {
            return false;
        }

        @Override
        public int getSymbolCapacity(int columnIndex) {
            return configuration.getDefaultSymbolCapacity();
        }

        @Override
        public CharSequence getTableName() {
            return tableName;
        }

        @Override
        public int getTimestampIndex() {
            return timestampColumnIndex;
        }

        @Override
        public int getMaxUncommittedRows() {
            return configuration.getMaxUncommittedRows();
        }

        @Override
        public long getCommitLag() {
            return configuration.getCommitLag();
        }

        public void of(final CharSequence tableName,
                       final ObjList<CharSequence> names,
                       final ObjList<TypeAdapter> types,
                       final int timestampColumnIndex,
                       final int partitionBy
        ) {
            this.tableName = tableName;
            this.columnNames = names;

            this.columnBits.clear();
            for (int i = 0, size = types.size(); i < size; i++) {
                final TypeAdapter adapter = types.getQuick(i);
                this.columnBits.add(Numbers.encodeLowHighInts(adapter.getType(), adapter.isIndexed() ? 1 : 0));
            }

            this.timestampColumnIndex = timestampColumnIndex;
            this.partitionBy = partitionBy;
        }

        public void setIgnoreColumnIndexedFlag(boolean flag) {
            this.ignoreColumnIndexedFlag = flag;
        }

    }

    public static class TaskContext implements Closeable, Mutable {
        private final DirectLongList mergeIndexes = new DirectLongList(64, MemoryTag.NATIVE_LONG_LIST);
        private final FileSplitter splitter;
        private final TextLexer lexer;

        private final TypeManager typeManager;
        private final DirectCharSink utf8Sink;

        private final Path path = new Path();

        private ObjList<TypeAdapter> types;
        private TimestampAdapter timestampAdapter;
        private final CairoEngine cairoEngine;
        private TableWriter tableWriterRef;
        private final StringSink tableNameSink = new StringSink();
        private int timestampIndex;
        private TableStructureAdapter targetTableStructure;
        private int atomicity;

        public TaskContext(CairoEngine cairoEngine) {
            this.cairoEngine = cairoEngine;
            final CairoConfiguration configuration = cairoEngine.getConfiguration();
            final TextConfiguration textConfiguration = configuration.getTextConfiguration();
            this.utf8Sink = new DirectCharSink(textConfiguration.getUtf8SinkSize());
            this.typeManager = new TypeManager(textConfiguration, utf8Sink);
            this.splitter = new FileSplitter(configuration);
            this.lexer = new TextLexer(textConfiguration, typeManager);
        }

        public void buildColumnIndexesStage(int index, RecordMetadata metadata) {
            final CairoConfiguration configuration = cairoEngine.getConfiguration();
            tableNameSink.clear();
            tableNameSink.put(targetTableStructure.getTableName()).put('_').put(index);
            final int columnCount = metadata.getColumnCount();
            try (TableWriter w = new TableWriter(configuration,
                    tableNameSink,
                    cairoEngine.getMessageBus(),
                    null,
                    true,
                    DefaultLifecycleManager.INSTANCE,
                    splitter.getImportRoot(),
                    cairoEngine.getMetrics())) {
                for (int i = 0; i < columnCount; i++) {
                    if (metadata.isColumnIndexed(i)) {
                        w.addIndex(metadata.getColumnName(i), metadata.getIndexValueBlockCapacity(i));
                    }
                }
            }
        }

        public void buildIndexStage(long lo, long hi, long lineNumber, LongList indexStats, int index, LongList partitionKeys) throws SqlException {
            splitter.index(lo, hi, lineNumber, indexStats, index, partitionKeys);
        }

        public void importPartitionData(long address, long size, int len) {
            final CairoConfiguration configuration = cairoEngine.getConfiguration();
            final FilesFacade ff = configuration.getFilesFacade();
            long buf = Unsafe.malloc(len, MemoryTag.NATIVE_DEFAULT);
            try {
                path.of(configuration.getInputRoot()).concat(splitter.getInputFileName()).$();
                long fd = ff.openRO(path);
                try {
                    final long count = size / (2 * Long.BYTES);
                    for (long i = 0; i < count; i++) {
                        final long offset = Unsafe.getUnsafe().getLong(address + i * 2L * Long.BYTES + Long.BYTES);
                        long n = ff.read(fd, buf, len, offset);
                        if (n > 0) {
                            lexer.parse(buf, buf + n, 0, this::onFieldsPartitioned);
                        }
                    }
                } finally {
                    ff.close(fd);
                }
            } finally {
                Unsafe.free(buf, len, MemoryTag.NATIVE_DEFAULT);
            }
        }

        @Override
        public void close() throws IOException {
            clear();
            mergeIndexes.close();
            splitter.close();
            lexer.close();
            utf8Sink.close();
            path.close();
        }

        public void countQuotesStage(int index, long lo, long hi, final LongList chunkStats) throws TextException {
            splitter.countQuotes(lo, hi, chunkStats, index);
        }

        public void importPartitionStage(int index, long lo, long hi, final ObjList<CharSequence> partitionNames, int maxLineLength) {
            tableNameSink.clear();
            tableNameSink.put(targetTableStructure.getTableName()).put('_').put(index);
            final CairoConfiguration configuration = cairoEngine.getConfiguration();
            final FilesFacade ff = configuration.getFilesFacade();
            final CharSequence importRoot = splitter.getImportRoot();
            createTable(ff, configuration.getMkDirMode(), importRoot, tableNameSink, targetTableStructure, 0);
            setCurrentTableName(tableNameSink);
            try (TableWriter writer = new TableWriter(configuration,
                    tableNameSink,
                    cairoEngine.getMessageBus(),
                    null,
                    true,
                    DefaultLifecycleManager.INSTANCE,
                    importRoot,
                    cairoEngine.getMetrics())) {

                tableWriterRef = writer;
                try {
                    lexer.restart(false);
                    for (int i = (int) lo; i < hi; i++) {

                        final CharSequence name = partitionNames.get(i);
                        path.of(importRoot).concat(name);

                        mergePartitionIndexAndImportData(ff, path, mergeIndexes, maxLineLength);
                    }
                } finally {
                    lexer.parseLast();
                    writer.commit(CommitMode.SYNC);
                }
            }
        }

        @Override
        public void clear() {
            mergeIndexes.clear();
            splitter.clear();
            lexer.clear();
            typeManager.clear();
            utf8Sink.clear();
            tableNameSink.clear();
            if (types != null) {
                types.clear();
            }
            timestampAdapter = null;
        }

        public void of(int index, CharSequence importRoot, CharSequence inputFileName, TableStructureAdapter structure, ObjList<TypeAdapter> types, boolean forceHeader, byte delimiter, int atomicity) {
            this.targetTableStructure = structure;
            this.types = typeManager.adjust(types);
            this.timestampIndex = targetTableStructure.getTimestampIndex();
            this.timestampAdapter = (timestampIndex > -1 && timestampIndex < types.size()) ? (TimestampAdapter) types.getQuick(timestampIndex) : null;
            this.lexer.of(delimiter);
            this.lexer.setSkipLinesWithExtraValues(false);
            this.splitter.of(inputFileName, importRoot, index, targetTableStructure.getPartitionBy(), delimiter, timestampIndex, timestampAdapter, forceHeader);
            this.atomicity = atomicity;
        }

        public void updateSymbolKeys(int index, long partitionSize, long partitionTimestamp, CharSequence symbolColumnName, int symbolCount) {
            final FilesFacade ff = cairoEngine.getConfiguration().getFilesFacade();
            Path path = Path.getThreadLocal(splitter.getImportRoot());
            path.concat(targetTableStructure.getTableName()).put("_").put(index);
            int plen = path.length();
            PartitionBy.setSinkForPartition(path.slash(), targetTableStructure.getPartitionBy(), partitionTimestamp, false);
            path.concat(symbolColumnName).put(TableUtils.FILE_SUFFIX_D);

            long columnMemory = 0;
            long columnMemorySize = 0;
            long remapTableMemory = 0;
            long remapTableMemorySize = 0;
            long columnFd = -1;
            long remapFd = -1;
            try {
                columnFd = TableUtils.openFileRWOrFail(ff, path.$(), CairoConfiguration.O_NONE);
                columnMemorySize = ff.length(columnFd);

                path.trimTo(plen);
                path.concat(symbolColumnName).put(TableUtils.SYMBOL_KEY_REMAP_FILE_SUFFIX);
                remapFd = TableUtils.openFileRWOrFail(ff, path.$(), CairoConfiguration.O_NONE);
                remapTableMemorySize = ff.length(remapFd);

                if (columnMemorySize >= Integer.BYTES && remapTableMemorySize >= Integer.BYTES) {
                    columnMemory = TableUtils.mapRW(ff, columnFd, columnMemorySize, MemoryTag.MMAP_DEFAULT);
                    remapTableMemory = TableUtils.mapRW(ff, remapFd, remapTableMemorySize, MemoryTag.MMAP_DEFAULT);
                    long columnMemSize = partitionSize * Integer.BYTES;
                    long remapMemSize = (long) symbolCount * Integer.BYTES;
                    ColumnUtils.symbolColumnUpdateKeys(columnMemory, columnMemSize, remapTableMemory, remapMemSize);
                }
            } finally {
                if (columnFd != -1) {
                    ff.close(columnFd);
                }
                if (remapFd != -1) {
                    ff.close(remapFd);
                }
                if (columnMemory > 0) {
                    ff.munmap(columnMemory, columnMemorySize, MemoryTag.MMAP_DEFAULT);
                }
                if (remapTableMemory > 0) {
                    ff.munmap(remapTableMemory, remapTableMemorySize, MemoryTag.MMAP_DEFAULT);
                }
            }
        }

        public void setCurrentTableName(final CharSequence tableName) {
            this.lexer.setTableName(tableName);
        }

        private void logError(long line, int i, final DirectByteCharSequence dbcs) {
            LogRecord logRecord = LOG.error().$("type syntax [type=").$(ColumnType.nameOf(types.getQuick(i).getType())).$("]\t");
            logRecord.$('[').$(line).$(':').$(i).$("] -> ").$(dbcs).$();
        }

        private void mergePartitionIndexAndImportData(final FilesFacade ff,
                                                      final Path partitionPath,
                                                      final DirectLongList mergeIndexes,
                                                      int maxLineLength) {
            mergeIndexes.resetCapacity();
            mergeIndexes.clear();

            partitionPath.slash$();
            int partitionLen = partitionPath.length();

            long mergedIndexSize = openIndexChunks(ff, partitionPath, mergeIndexes, partitionLen);

            long address = -1;
            try {
                final int indexesCount = (int) mergeIndexes.size() / 2;
                partitionPath.trimTo(partitionLen);
                partitionPath.concat(FileSplitter.INDEX_FILE_NAME).$();

                final long fd = TableUtils.openFileRWOrFail(ff, partitionPath, CairoConfiguration.O_NONE);
                address = TableUtils.mapRW(ff, fd, mergedIndexSize, MemoryTag.MMAP_DEFAULT);
                ff.close(fd);

                final long merged = Vect.mergeLongIndexesAscExt(mergeIndexes.getAddress(), indexesCount, address);
                importPartitionData(merged, mergedIndexSize, maxLineLength);
            } finally {
                if (address != -1) {
                    ff.munmap(address, mergedIndexSize, MemoryTag.MMAP_DEFAULT);
                }
                for (long i = 0, sz = mergeIndexes.size() / 2; i < sz; i++) {
                    final long addr = mergeIndexes.get(2 * i);
                    final long size = mergeIndexes.get(2 * i + 1) * FileSplitter.INDEX_ENTRY_SIZE;
                    ff.munmap(addr, size, MemoryTag.MMAP_DEFAULT);
                }
            }
        }

        private boolean onField(long line, final DirectByteCharSequence dbcs, TableWriter.Row w, int i) {
            try {
                types.getQuick(i).write(w, i, dbcs);
            } catch (Exception ignore) {
                logError(line, i, dbcs);
                switch (atomicity) {
                    case Atomicity.SKIP_ALL:
                        tableWriterRef.rollback();
                        throw CairoException.instance(0).put("bad syntax [line=").put(line).put(", col=").put(i).put(']');
                    case Atomicity.SKIP_ROW:
                        w.cancel();
                        return true;
                    default:
                        // SKIP column
                        break;
                }
            }
            return false;
        }

        private void onFieldsPartitioned(long line, final ObjList<DirectByteCharSequence> values, int valuesLength) {
            assert tableWriterRef != null;
            DirectByteCharSequence dbcs = values.getQuick(timestampIndex);
            try {
                final TableWriter.Row w = tableWriterRef.newRow(timestampAdapter.getTimestamp(dbcs));
                for (int i = 0; i < valuesLength; i++) {
                    dbcs = values.getQuick(i);
                    if (i == timestampIndex || dbcs.length() == 0) {
                        continue;
                    }
                    if (onField(line, dbcs, w, i)) return;
                }
                w.append();
            } catch (Exception e) {
                logError(line, timestampIndex, dbcs);
            }
        }

        private long openIndexChunks(FilesFacade ff, Path partitionPath, DirectLongList mergeIndexes, int partitionLen) {
            long mergedIndexSize = 0;
            long chunk = ff.findFirst(partitionPath);
            if (chunk > 0) {
                try {
                    do {
                        // chunk loop
                        long chunkName = ff.findName(chunk);
                        long chunkType = ff.findType(chunk);
                        if (chunkType == Files.DT_FILE) {
                            partitionPath.trimTo(partitionLen);
                            partitionPath.concat(chunkName).$();
                            final long fd = TableUtils.openRO(ff, partitionPath, LOG);
                            final long size = ff.length(fd);
                            final long address = TableUtils.mapRO(ff, fd, size, MemoryTag.MMAP_DEFAULT);
                            ff.close(fd);

                            mergeIndexes.add(address);
                            mergeIndexes.add(size / FileSplitter.INDEX_ENTRY_SIZE);
                            mergedIndexSize += size;
                        }
                    } while (ff.findNext(chunk) > 0);
                } finally {
                    ff.findClose(chunk);
                }
            }
            return mergedIndexSize;
        }
    }
}
