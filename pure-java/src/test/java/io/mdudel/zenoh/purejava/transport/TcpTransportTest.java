/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.transport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Loopback-server integration tests for {@link TcpTransport}. Every
 * test binds an ephemeral port on {@code 127.0.0.1}, runs one accept
 * on a dedicated thread, and asserts the on-wire bytes against
 * {@link StreamFramer}'s contract.
 */
class TcpTransportTest {

    private ServerSocket server;
    private int          port;

    @BeforeEach void bind() throws IOException {
        server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        port   = server.getLocalPort();
    }

    @AfterEach void unbind() throws IOException {
        if (server != null && !server.isClosed()) server.close();
    }

    @Test void connectSendReceiveRoundtrip() throws Exception {
        AtomicReference<byte[]>  serverGot = new AtomicReference<>();
        CountDownLatch           connected = new CountDownLatch(1);
        Thread accept = new Thread(() -> {
            try (Socket peer = server.accept()) {
                connected.countDown();
                InputStream  in  = peer.getInputStream();
                OutputStream out = peer.getOutputStream();
                // Read one frame from client.
                byte[] batch = StreamFramer.readFrame(in);
                serverGot.set(batch);
                // Reply with a hand-crafted frame.
                StreamFramer.writeFrame(out, new byte[] { 'p', 'o', 'n', 'g' });
                out.flush();
                // Wait a moment for the client to read; then remote-close.
                Thread.sleep(50);
            } catch (Exception e) {
                serverGot.set(null);
            }
        }, "loopback-server");
        accept.setDaemon(true);
        accept.start();

        try (TcpTransport t = new TcpTransport("127.0.0.1", port)) {
            t.connect();
            assertTrue(connected.await(2, TimeUnit.SECONDS), "server did not accept");
            assertTrue(t.isOpen());
            assertEquals("tcp/127.0.0.1:" + port, t.describe());

            t.send(new byte[] { 'p', 'i', 'n', 'g' });

            byte[] reply = t.receive(2, TimeUnit.SECONDS);
            assertArrayEquals(new byte[] { 'p', 'o', 'n', 'g' }, reply);
        }
        accept.join(2_000);
        assertArrayEquals(new byte[] { 'p', 'i', 'n', 'g' }, serverGot.get());
    }

    @Test void connectRefusedThrowsTransportException() throws IOException {
        // Bind then immediately close so the port is guaranteed reserved-but-refusing.
        int refusedPort;
        try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            refusedPort = s.getLocalPort();
        }
        try (TcpTransport t = new TcpTransport("127.0.0.1", refusedPort)) {
            t.setConnectTimeoutMs(500);
            TransportException e = assertThrows(TransportException.class, t::connect);
            // Message from AbstractStreamTransport reads "connect failed to tcp/host:port: ..."
            assertTrue(e.getMessage().contains("connect failed"),
                    "message was: " + e.getMessage());
            assertTrue(e.getMessage().contains("tcp/"),
                    "message should mention tcp/ describe(): " + e.getMessage());
        }
    }

    @Test void sendOnClosedTransportThrows() throws Exception {
        Thread accept = new Thread(() -> {
            try (Socket ignored = server.accept()) { /* accept and hold */ Thread.sleep(200); }
            catch (Exception ignored) {}
        });
        accept.setDaemon(true);
        accept.start();

        TcpTransport t = new TcpTransport("127.0.0.1", port);
        t.connect();
        t.close();
        assertFalse(t.isOpen());
        assertThrows(TransportException.class,
                () -> t.send(new byte[] { 'x' }));
        accept.join(2_000);
    }

    @Test void sendOversizedBatchRejected() throws Exception {
        Thread accept = new Thread(() -> {
            try (Socket ignored = server.accept()) { Thread.sleep(200); }
            catch (Exception ignored) {}
        });
        accept.setDaemon(true);
        accept.start();

        try (TcpTransport t = new TcpTransport("127.0.0.1", port)) {
            t.connect();
            byte[] tooBig = new byte[StreamFramer.MAX_FRAME_BYTES + 1];
            TransportException e = assertThrows(TransportException.class,
                    () -> t.send(tooBig));
            assertTrue(e.getMessage().contains("wire cap"), "message was: " + e.getMessage());
            assertTrue(t.isOpen(), "oversized rejection must not close the transport");
        }
        accept.join(2_000);
    }

    @Test void receiveReturnsNullOnTimeoutWhenServerQuiet() throws Exception {
        CountDownLatch keepAlive = new CountDownLatch(1);
        Thread accept = new Thread(() -> {
            try (Socket peer = server.accept()) {
                // Just hold the connection; never write.
                keepAlive.await(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        });
        accept.setDaemon(true);
        accept.start();

        try (TcpTransport t = new TcpTransport("127.0.0.1", port)) {
            t.connect();
            long t0 = System.nanoTime();
            byte[] got = t.receive(150, TimeUnit.MILLISECONDS);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            assertNull(got);
            assertTrue(elapsedMs >= 100, "timeout returned too early: " + elapsedMs + " ms");
            assertTrue(t.isOpen(), "timeout must not close the transport");
        }
        keepAlive.countDown();
        accept.join(2_000);
    }

    @Test void remoteCleanCloseSurfacesAsNullAndClosesTransport() throws Exception {
        Thread accept = new Thread(() -> {
            try (Socket peer = server.accept()) {
                // Close immediately without writing.
            } catch (Exception ignored) {}
        });
        accept.setDaemon(true);
        accept.start();

        try (TcpTransport t = new TcpTransport("127.0.0.1", port)) {
            t.connect();
            byte[] got = t.receive(2, TimeUnit.SECONDS);
            assertNull(got, "clean remote close should surface as null");
            // isOpen may lag by a millisecond as reader thread runs finally block; give it a nudge.
            long deadline = System.currentTimeMillis() + 1_000;
            while (t.isOpen() && System.currentTimeMillis() < deadline) Thread.sleep(10);
            assertFalse(t.isOpen(), "transport should have closed after remote EOF");
        }
        accept.join(2_000);
    }

    @Test void twoConcurrentSendersSerialiseOnWriteLock() throws Exception {
        int frames = 20;
        AtomicReference<Throwable> serverErr = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread accept = new Thread(() -> {
            try (Socket peer = server.accept()) {
                InputStream in = peer.getInputStream();
                for (int i = 0; i < frames; i++) {
                    byte[] batch = StreamFramer.readFrame(in);
                    // Each batch is exactly 4 bytes and has a magic byte marking the thread.
                    if (batch.length != 4) fail("unexpected batch len " + batch.length);
                }
                done.countDown();
            } catch (Throwable e) {
                serverErr.set(e);
            }
        });
        accept.setDaemon(true);
        accept.start();

        try (TcpTransport t = new TcpTransport("127.0.0.1", port)) {
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
            a.join(2_000); b.join(2_000);
            assertTrue(done.await(2, TimeUnit.SECONDS), "server did not receive all frames");
        }
        assertNull(serverErr.get(), "server saw framing error: " + serverErr.get());
        accept.join(2_000);
    }

    @Test void closeIsIdempotent() throws Exception {
        Thread accept = new Thread(() -> {
            try (Socket ignored = server.accept()) { Thread.sleep(200); }
            catch (Exception ignored) {}
        });
        accept.setDaemon(true);
        accept.start();

        TcpTransport t = new TcpTransport("127.0.0.1", port);
        t.connect();
        t.close();
        t.close();  // must not throw
        t.close();
        assertFalse(t.isOpen());
        accept.join(2_000);
    }

    @Test void invalidPortRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new TcpTransport("h", 0));
        assertThrows(IllegalArgumentException.class, () -> new TcpTransport("h", 65_536));
        assertThrows(IllegalArgumentException.class, () -> new TcpTransport("h", -1));
    }

    @Test void doubleConnectIsRejected() throws Exception {
        Thread accept = new Thread(() -> {
            try (Socket ignored = server.accept()) { Thread.sleep(200); }
            catch (Exception ignored) {}
        });
        accept.setDaemon(true);
        accept.start();

        try (TcpTransport t = new TcpTransport("127.0.0.1", port)) {
            t.connect();
            assertThrows(IllegalStateException.class, t::connect);
        }
        accept.join(2_000);
    }

    @Test void multiFrameRoundtripDeliversInOrder() throws Exception {
        int frames = 5;
        Thread accept = new Thread(() -> {
            try (Socket peer = server.accept()) {
                OutputStream out = peer.getOutputStream();
                for (int i = 0; i < frames; i++) {
                    StreamFramer.writeFrame(out, new byte[] { (byte) i });
                }
                out.flush();
                Thread.sleep(100);
            } catch (Exception ignored) {}
        });
        accept.setDaemon(true);
        accept.start();

        try (TcpTransport t = new TcpTransport("127.0.0.1", port)) {
            t.connect();
            for (int i = 0; i < frames; i++) {
                byte[] batch = t.receive(2, TimeUnit.SECONDS);
                assertNotNull(batch, "frame " + i + " missing");
                assertEquals(1, batch.length);
                assertEquals((byte) i, batch[0]);
            }
        }
        accept.join(2_000);
    }
}
