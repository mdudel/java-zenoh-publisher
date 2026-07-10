/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.transport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loopback integration tests for {@link WsTransport}. Uses the minimal
 * RFC 6455 server in {@link LoopbackWebSocketServer} so we exercise a
 * real WebSocket handshake + real binary frames end to end, without
 * pulling any third-party dependency.
 */
class WsTransportTest {

    private static final char[] PW = "changeit".toCharArray();

    private LoopbackWebSocketServer server;

    @BeforeEach void bindPlain() throws IOException {
        server = LoopbackWebSocketServer.bindPlain();
    }

    @AfterEach void unbind() { if (server != null) server.close(); }

    @Test void roundtripBinaryOverWs() throws Exception {
        CountDownLatch sawSession = new CountDownLatch(1);
        server.onSession((s, srv) -> {
            sawSession.countDown();
            try {
                byte[] got = s.inbound.poll(3, TimeUnit.SECONDS);
                if (got == null) return;
                s.sendBinary(new byte[] { 'p', 'o', 'n', 'g' });
            } catch (Exception ignored) {}
        });

        URI uri = URI.create("ws://127.0.0.1:" + server.port());
        try (WsTransport t = new WsTransport(uri, null)) {
            t.connect();
            assertTrue(t.isOpen());
            assertEquals("ws/127.0.0.1:" + server.port(), t.describe());
            assertTrue(sawSession.await(3, TimeUnit.SECONDS));

            t.send(new byte[] { 'p', 'i', 'n', 'g' });
            byte[] reply = t.receive(3, TimeUnit.SECONDS);
            assertArrayEquals(new byte[] { 'p', 'o', 'n', 'g' }, reply);
        }
    }

    @Test void multiFrameInOrderDelivery() throws Exception {
        int n = 5;
        server.onSession((s, srv) -> {
            try {
                for (int i = 0; i < n; i++) s.sendBinary(new byte[] { (byte) i });
            } catch (Exception ignored) {}
        });

        URI uri = URI.create("ws://127.0.0.1:" + server.port());
        try (WsTransport t = new WsTransport(uri, null)) {
            t.connect();
            for (int i = 0; i < n; i++) {
                byte[] frame = t.receive(3, TimeUnit.SECONDS);
                assertNotNull(frame, "frame " + i + " missing");
                assertEquals(1, frame.length);
                assertEquals((byte) i, frame[0]);
            }
        }
    }

    @Test void fragmentedInboundMessageReassembles() throws Exception {
        byte[] full = new byte[10_000];
        for (int i = 0; i < full.length; i++) full[i] = (byte) (i * 31);
        server.onSession((s, srv) -> {
            try { s.sendBinaryFragmented(full, 1024); }
            catch (Exception ignored) {}
        });

        URI uri = URI.create("ws://127.0.0.1:" + server.port());
        try (WsTransport t = new WsTransport(uri, null)) {
            t.connect();
            byte[] got = t.receive(3, TimeUnit.SECONDS);
            assertArrayEquals(full, got, "reassembled fragmented message must equal original");
        }
    }

    @Test void twoConcurrentSendersSerialiseCleanlyOnWebSocket() throws Exception {
        int frames = 20;
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> serverErr = new AtomicReference<>();
        server.onSession((s, srv) -> {
            try {
                for (int i = 0; i < frames; i++) {
                    byte[] batch = s.inbound.poll(3, TimeUnit.SECONDS);
                    if (batch == null || batch.length != 4) {
                        serverErr.set(new AssertionError(
                                "unexpected batch: " + (batch == null ? "null" : batch.length + "B")));
                        return;
                    }
                }
                done.countDown();
            } catch (Exception e) { serverErr.set(e); }
        });

        URI uri = URI.create("ws://127.0.0.1:" + server.port());
        try (WsTransport t = new WsTransport(uri, null)) {
            t.connect();
            Thread a = new Thread(() -> {
                for (int i = 0; i < frames / 2; i++) {
                    try { t.send(new byte[] { 'A', (byte) i, 0, 0 }); }
                    catch (Exception e) { throw new RuntimeException(e); }
                }
            });
            Thread b = new Thread(() -> {
                for (int i = 0; i < frames / 2; i++) {
                    try { t.send(new byte[] { 'B', (byte) i, 0, 0 }); }
                    catch (Exception e) { throw new RuntimeException(e); }
                }
            });
            a.start(); b.start();
            a.join(3_000); b.join(3_000);
            assertTrue(done.await(3, TimeUnit.SECONDS),
                    "server did not receive all frames" +
                    (serverErr.get() == null ? "" : "; err=" + serverErr.get()));
        }
        assertNull(serverErr.get(), "server saw framing error: " + serverErr.get());
    }

    @Test void serverCleanCloseSurfacesAsNullReceiveAndClosesTransport() throws Exception {
        server.onSession((s, srv) -> {
            try { Thread.sleep(50); s.sendClose(1000); }
            catch (Exception ignored) {}
        });

        URI uri = URI.create("ws://127.0.0.1:" + server.port());
        try (WsTransport t = new WsTransport(uri, null)) {
            t.connect();
            byte[] got = t.receive(3, TimeUnit.SECONDS);
            assertNull(got, "clean server close should surface as null");
            long deadline = System.currentTimeMillis() + 1_000;
            while (t.isOpen() && System.currentTimeMillis() < deadline) Thread.sleep(10);
            assertFalse(t.isOpen(), "transport should have closed after server close frame");
        }
    }

    @Test void serverTextFrameCausesReaderError() throws Exception {
        server.onSession((s, srv) -> {
            try { Thread.sleep(50); s.sendText("i am wrong protocol"); Thread.sleep(200); }
            catch (Exception ignored) {}
        });

        URI uri = URI.create("ws://127.0.0.1:" + server.port());
        try (WsTransport t = new WsTransport(uri, null)) {
            t.connect();
            // The text frame triggers reportReaderError + close.
            TransportException e = assertThrows(TransportException.class,
                    () -> {
                        // receive once for the null; a second call surfaces the error.
                        t.receive(2, TimeUnit.SECONDS);
                        Thread.sleep(50);
                        t.receive(1, TimeUnit.SECONDS);
                    });
            assertTrue(e.getMessage().contains("text frame")
                            || e.getMessage().contains("reader ended"),
                    "message was: " + e.getMessage());
        }
    }

    @Test void oversizedBatchRejectedAtWsLayer() throws Exception {
        server.onSession((s, srv) -> {
            try { Thread.sleep(500); }
            catch (InterruptedException ignored) {}
        });

        URI uri = URI.create("ws://127.0.0.1:" + server.port());
        try (WsTransport t = new WsTransport(uri, null)) {
            t.connect();
            byte[] tooBig = new byte[StreamFramer.MAX_FRAME_BYTES + 1];
            TransportException e = assertThrows(TransportException.class, () -> t.send(tooBig));
            assertTrue(e.getMessage().contains("wire cap"),
                    "message was: " + e.getMessage());
            assertTrue(t.isOpen(), "oversized rejection must not close the transport");
        }
    }

    @Test void maxSizedBatchRoundtrips() throws Exception {
        // Zenoh cap = 65535 bytes; verify a full-sized message survives WebSocket framing.
        byte[] max = new byte[StreamFramer.MAX_FRAME_BYTES];
        for (int i = 0; i < max.length; i++) max[i] = (byte) (i & 0xFF);
        server.onSession((s, srv) -> {
            try {
                byte[] got = s.inbound.poll(3, TimeUnit.SECONDS);
                if (got != null) s.sendBinary(got);
            } catch (Exception ignored) {}
        });

        URI uri = URI.create("ws://127.0.0.1:" + server.port());
        try (WsTransport t = new WsTransport(uri, null)) {
            t.connect();
            t.send(max);
            byte[] echoed = t.receive(3, TimeUnit.SECONDS);
            assertArrayEquals(max, echoed);
        }
    }

    @Test void wssRoundtripsOverTls() throws Exception {
        // Server: TLS-wrapped WebSocket on 127.0.0.1 using the same PKCS12
        // keystores as TlsTransportTest.
        SSLContext ctx = serverSslContext();
        try (LoopbackWebSocketServer wss = LoopbackWebSocketServer.bindTls(ctx)) {
            wss.onSession((s, srv) -> {
                try {
                    byte[] got = s.inbound.poll(3, TimeUnit.SECONDS);
                    if (got != null) s.sendBinary(got);
                } catch (Exception ignored) {}
            });

            TlsConfig tls = TlsConfig.builder()
                    .trustStore(resource("client-trust.p12"), PW)
                    .build();
            URI uri = URI.create("wss://127.0.0.1:" + wss.port());
            try (WsTransport t = new WsTransport(uri, tls)) {
                t.connect();
                assertEquals("wss/127.0.0.1:" + wss.port(), t.describe());
                t.send(new byte[] { 'x', 'y', 'z' });
                byte[] back = t.receive(3, TimeUnit.SECONDS);
                assertArrayEquals(new byte[] { 'x', 'y', 'z' }, back);
            }
        }
    }

    @Test void invalidSchemeRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new WsTransport(URI.create("http://127.0.0.1:80"), null));
        assertThrows(IllegalArgumentException.class,
                () -> new WsTransport(URI.create("tcp://127.0.0.1:7447"), null));
    }

    @Test void wssRequiresTlsConfig() {
        assertThrows(IllegalArgumentException.class,
                () -> new WsTransport(URI.create("wss://127.0.0.1:9000"), null));
    }

    @Test void wsRejectsTlsConfig() throws Exception {
        TlsConfig tls = TlsConfig.builder()
                .trustStore(resource("client-trust.p12"), PW)
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> new WsTransport(URI.create("ws://127.0.0.1:9000"), tls));
    }

    @Test void missingHostOrPortRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new WsTransport(URI.create("ws:///no-host"), null));
        assertThrows(IllegalArgumentException.class,
                () -> new WsTransport(URI.create("ws://127.0.0.1"), null));
    }

    @Test void connectRefusedThrowsTransportException() throws Exception {
        // Bind and immediately close so the port is guaranteed refusing.
        int refusedPort;
        try (LoopbackWebSocketServer s = LoopbackWebSocketServer.bindPlain()) {
            refusedPort = s.port();
        }
        URI uri = URI.create("ws://127.0.0.1:" + refusedPort);
        try (WsTransport t = new WsTransport(uri, null)
                .setOperationTimeoutMs(2_000)) {
            TransportException e = assertThrows(TransportException.class, t::connect);
            assertTrue(e.getMessage().contains("connect failed")
                            || e.getMessage().contains("handshake"),
                    "message was: " + e.getMessage());
        }
    }

    @Test void sendOnClosedTransportRejects() throws Exception {
        server.onSession((s, srv) -> {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        });
        URI uri = URI.create("ws://127.0.0.1:" + server.port());
        WsTransport t = new WsTransport(uri, null);
        t.connect();
        t.close();
        assertThrows(TransportException.class, () -> t.send(new byte[] { 'x' }));
    }

    @Test void closeIsIdempotent() throws Exception {
        server.onSession((s, srv) -> {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        });
        URI uri = URI.create("ws://127.0.0.1:" + server.port());
        WsTransport t = new WsTransport(uri, null);
        t.connect();
        t.close();
        t.close(); // must not throw
        t.close();
        assertFalse(t.isOpen());
    }

    // ---- helpers ------------------------------------------------------

    private static Path resource(String name) {
        URL u = WsTransportTest.class.getClassLoader().getResource(name);
        if (u == null) throw new IllegalStateException("test resource missing: " + name);
        // Use URI, NOT URL.getPath(). On Windows the latter returns
        // "/D:/dir/file.p12" (leading slash before drive letter), which
        // Paths.get rejects with InvalidPathException on the ':' at index 2.
        // Paths.get(URI) understands the file: URI properly on all platforms.
        try { return Paths.get(u.toURI()); }
        catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("cannot convert resource URL to URI: " + u, e);
        }
    }

    private static SSLContext serverSslContext() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = java.nio.file.Files.newInputStream(resource("server.p12"))) {
            ks.load(in, PW);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PW);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
        return ctx;
    }
}
