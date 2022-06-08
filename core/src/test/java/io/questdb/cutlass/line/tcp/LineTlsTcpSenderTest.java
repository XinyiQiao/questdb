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

import io.questdb.cairo.TableReader;
import io.questdb.cairo.security.AllowAllCairoSecurityContext;
import io.questdb.cutlass.line.LineTcpSender;
import io.questdb.test.tools.TestUtils;
import org.junit.ClassRule;
import org.junit.Test;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.junit.Assert.assertEquals;

public class LineTlsTcpSenderTest extends AbstractLineTcpReceiverTest {

    private static final String AUTH_KEY_ID1 = "testUser1";
    private static final String TOKEN = "UvuVb1USHGRRT08gEnwN2zGZrvM4MsLQ5brgF6SVkAw=";
    private static final DockerImageName HA_PROXY_IMAGE = DockerImageName.parse("haproxy:2.6.0");
    private static final String TRUSTSTORE_PATH = "/keystore/haproxy_ca.jks";
    private static final char[] TRUSTSTORE_PASSWORD = "questdb".toCharArray();


    @ClassRule
    public static GenericContainer<?> haProxy = new GenericContainer<>(HA_PROXY_IMAGE)
            .withClasspathResourceMapping("/io/questdb/cutlass/line/tcp/haproxy.pem", "/usr/local/etc/haproxy/haproxy.pem", BindMode.READ_ONLY)
            .withClasspathResourceMapping("/io/questdb/cutlass/line/tcp/haproxy.cfg", "/usr/local/etc/haproxy/haproxy.cfg", BindMode.READ_ONLY)
            .withExposedPorts(8443)
            .withAccessToHost(true);

    @Test
    public void simpleTest() throws Exception {
        authKeyId = AUTH_KEY_ID1;
        String tableName = UUID.randomUUID().toString();
        runInContext(c -> {
            Testcontainers.exposeHostPorts(9002);

            try (LineTcpSender sender = LineTcpSender.builder()
                    .enableTls()
                    .address(haProxy.getHost())
                    .port(haProxy.getMappedPort(8443))
                    .token(TOKEN)
                    .customTrustStore("classpath:" + TRUSTSTORE_PATH, TRUSTSTORE_PASSWORD)
                    .build()) {
                sender.metric(tableName).field("value", 42).$();
                sender.flush();
            }
            assertTableExistsEventually(engine, tableName);
        });
    }

    @Test
    public void testWithoutExplicitFlushing() throws Exception {
        // no explicit flushing results in high buffers utilization

        authKeyId = AUTH_KEY_ID1;
        String tableName = UUID.randomUUID().toString();
        int hugeBufferSize = 1024 * 1024;
        int rows = 1_000_000;
        runInContext(c -> {
            Testcontainers.exposeHostPorts(9002);

            try (LineTcpSender sender = LineTcpSender.builder()
                    .enableTls()
                    .bufferCapacity(hugeBufferSize)
                    .address(haProxy.getHost())
                    .port(haProxy.getMappedPort(8443))
                    .token(TOKEN)
                    .customTrustStore("classpath:" + TRUSTSTORE_PATH, TRUSTSTORE_PASSWORD)
                    .build()) {
                for (long l = 0; l < rows; l++) {
                    sender.metric(tableName).field("value", 42).$();
                }
                sender.flush();
            }
            assertTableSizeEventually(tableName, rows);
        });
    }

    @Test
    public void testWithCustomTruststoreByFilename() throws Exception {
        authKeyId = AUTH_KEY_ID1;
        String tableName = UUID.randomUUID().toString();
        String truststore = LineTlsTcpSenderTest.class.getResource(TRUSTSTORE_PATH).getFile();
        runInContext(c -> {
            Testcontainers.exposeHostPorts(9002);

            try (LineTcpSender sender = LineTcpSender.builder()
                    .enableTls()
                    .address(haProxy.getHost())
                    .port(haProxy.getMappedPort(8443))
                    .token(TOKEN)
                    .customTrustStore(truststore, TRUSTSTORE_PASSWORD)
                    .build()) {
                sender.metric(tableName).field("value", 42).$();
                sender.flush();
            }
            assertTableExistsEventually(engine, tableName);
        });
    }

    @Test
    public void testTinyTlsBuffers() throws Exception {
        String tableName = UUID.randomUUID().toString();
        int rows = 5_000;
        withCustomProperty(() -> {
            authKeyId = AUTH_KEY_ID1;
            runInContext(c -> {
                Testcontainers.exposeHostPorts(9002);

                try (LineTcpSender sender = LineTcpSender.builder()
                        .enableTls()
                        .address(haProxy.getHost())
                        .port(haProxy.getMappedPort(8443))
                        .token(TOKEN)
                        .customTrustStore("classpath:" + TRUSTSTORE_PATH, TRUSTSTORE_PASSWORD)
                        .build()) {

                    for (int i = 0; i < rows; i++) {
                        sender.metric(tableName).field("value", 42).$();
                        sender.flush();
                    }
                }
                assertTableSizeEventually(tableName, rows);
            });
        }, "questdb.experimental.tls.buffersize", "1");
    }

    private static void assertTableSizeEventually(String tableName, long expectedSize) {
        TestUtils.assertEventually(() -> {
            try (TableReader reader = engine.getReader(AllowAllCairoSecurityContext.INSTANCE, tableName)) {
                long size = reader.getCursor().size();
                assertEquals(expectedSize, size);
            }
        });
    }

    private interface RunnableWithException {
        void run() throws Exception;
    }

    private static void withCustomProperty(RunnableWithException runnable, String key, String value) throws Exception {
        String orig = System.getProperty(key);
        System.setProperty(key, value);
        try {
            runnable.run();
        } finally {
            if (orig != null) {
                System.setProperty(key, orig);
            } else {
                System.clearProperty(key);
            }
        }
    }
}