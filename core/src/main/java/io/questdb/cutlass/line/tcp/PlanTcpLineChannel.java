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

package io.questdb.cutlass.line.tcp;

import io.questdb.cutlass.line.LineChannel;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.network.NetworkError;
import io.questdb.network.NetworkFacade;

public final class PlanTcpLineChannel implements LineChannel {
    private static final Log LOG = LogFactory.getLog(PlanTcpLineChannel.class);

    private final NetworkFacade nf;
    private final long fd;
    private final long sockaddr;

    public PlanTcpLineChannel(NetworkFacade nf, int address, int port, int sndBufferSize) {
        this.nf = nf;
        this.sockaddr = nf.sockaddr(address, port);

        this.fd = nf.socketTcp(true);
        if (nf.connect(fd, sockaddr) != 0) {
            throw NetworkError.instance(nf.errno(), "could not connect to ").ip(address);
        }
        int orgSndBufSz = nf.getSndBuf(fd);
        nf.setSndBuf(fd, sndBufferSize);
        int newSndBufSz = nf.getSndBuf(fd);
        LOG.info().$("Send buffer size change from ").$(orgSndBufSz).$(" to ").$(newSndBufSz).$();
    }

    @Override
    public void close() {
        if (nf.close(fd) != 0) {
            LOG.error().$("could not close network socket [fd=").$(fd).$(", errno=").$(nf.errno()).$(']').$();
        }
        nf.freeSockAddr(sockaddr);
    }

    @Override
    public void send(long ptr, int len) {
        if (nf.send(fd, ptr, len) != len) {
            throw NetworkError.instance(nf.errno()).put("send error");
        }
    }

    @Override
    public int receive(long ptr, int len) {
        return nf.recv(fd, ptr, len);
    }

    @Override
    public int errno() {
        return nf.errno();
    }
}