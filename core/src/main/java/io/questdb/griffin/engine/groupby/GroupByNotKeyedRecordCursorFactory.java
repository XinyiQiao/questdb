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

package io.questdb.griffin.engine.groupby;

import io.questdb.cairo.AbstractRecordCursorFactory;
import io.questdb.cairo.sql.*;
import io.questdb.cairo.sql.Record;
import io.questdb.griffin.PlanSink;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.griffin.engine.functions.GroupByFunction;
import io.questdb.griffin.engine.functions.SymbolFunction;
import io.questdb.std.Misc;
import io.questdb.std.ObjList;

public class GroupByNotKeyedRecordCursorFactory extends AbstractRecordCursorFactory {

    protected final RecordCursorFactory base;
    private final GroupByNotKeyedRecordCursor cursor;
    private final ObjList<GroupByFunction> groupByFunctions;
    // this sink is used to copy recordKeyMap keys to dataMap
    private final SimpleMapValue simpleMapValue;
    private final VirtualRecord virtualRecordA;

    public GroupByNotKeyedRecordCursorFactory(
            RecordCursorFactory base,
            RecordMetadata groupByMetadata,
            ObjList<GroupByFunction> groupByFunctions,
            ObjList<Function> recordFunctions,
            int valueCount
    ) {
        super(groupByMetadata);
        this.simpleMapValue = new SimpleMapValue(valueCount);
        this.base = base;
        this.groupByFunctions = groupByFunctions;
        this.virtualRecordA = new VirtualRecordNoRowid(recordFunctions);
        this.virtualRecordA.of(simpleMapValue);
        this.cursor = new GroupByNotKeyedRecordCursor();
    }

    @Override
    protected void _close() {
        Misc.freeObjList(groupByFunctions);
        Misc.free(base);
    }

    @Override
    public RecordCursor getCursor(SqlExecutionContext executionContext) throws SqlException {
        final RecordCursor baseCursor = base.getCursor(executionContext);
        try {
            return cursor.of(baseCursor, executionContext);
        } catch (Throwable e) {
            Misc.free(baseCursor);
            throw e;
        }
    }

    @Override
    public boolean recordCursorSupportsRandomAccess() {
        return false;
    }

    @Override
    public boolean usesCompiledFilter() {
        return base.usesCompiledFilter();
    }

    @Override
    public void toPlan(PlanSink sink) {
        sink.type("GroupByNotKeyed");
        sink.meta("vectorized").val(false);
        sink.attr("groupByFunctions").val(groupByFunctions);
        sink.child(base);
    }

    private class GroupByNotKeyedRecordCursor implements NoRandomAccessRecordCursor {

        // hold on to reference of base cursor here
        // because we use it as symbol table source for the functions
        private RecordCursor baseCursor;
        private int recordsRemaining = 1;

        @Override
        public void close() {
            this.baseCursor = Misc.free(baseCursor);
        }

        @Override
        public SymbolTable getSymbolTable(int columnIndex) {
            return (SymbolTable) groupByFunctions.getQuick(columnIndex);
        }

        @Override
        public SymbolTable newSymbolTable(int columnIndex) {
            return ((SymbolFunction)groupByFunctions.getQuick(columnIndex)).newSymbolTable();
        }

        @Override
        public void toTop() {
            recordsRemaining = 1;
            GroupByUtils.toTop(groupByFunctions);
        }

        @Override
        public Record getRecord() {
            return virtualRecordA;
        }

        @Override
        public boolean hasNext() {
            return recordsRemaining-- > 0;
        }

        RecordCursor of(RecordCursor baseCursor, SqlExecutionContext executionContext) throws SqlException {
            this.baseCursor = baseCursor;
            final SqlExecutionCircuitBreaker circuitBreaker = executionContext.getCircuitBreaker();

            final Record baseRecord = baseCursor.getRecord();
            final int n = groupByFunctions.size();
            Function.init(groupByFunctions, baseCursor, executionContext);

            if (baseCursor.hasNext()) {
                GroupByUtils.updateNew(groupByFunctions, n, simpleMapValue, baseRecord);

                while (baseCursor.hasNext()) {
                    circuitBreaker.statefulThrowExceptionIfTripped();
                    GroupByUtils.updateExisting(groupByFunctions, n, simpleMapValue, baseRecord);
                }
            } else {
                GroupByUtils.updateEmpty(groupByFunctions, n, simpleMapValue);
            }

            toTop();
            return this;
        }

        @Override
        public long size() {
            return 1;
        }
    }
}
