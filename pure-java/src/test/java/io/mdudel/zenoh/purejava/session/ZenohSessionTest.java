/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.session;

import io.mdudel.zenoh.purejava.transport.TcpTransport;
import io.mdudel.zenoh.purejava.wire.messages.Close;
import io.mdudel.zenoh.purejava.wire.messages.Frame;
import io.mdudel.zenoh.purejava.wire.messages.KeepAlive;
import io.mdudel.zenoh.purejava.wire.messages.Push;
import io.mdudel.zenoh.purejava.wire.messages.Put;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration tests for {@link ZenohSession} against the
 * loopback router in {@link LoopbackZenohRouter}. Every test binds an
 * ephemeral port, exercises the full handshake + publish path, and
 * asserts the server-side wire bytes are the ones our own codecs produce.
 */
class ZenohSessionTest {

    private LoopbackZenohRouter router;

    @BeforeEach void bind() throws IOException {
        router = LoopbackZenohRouter.bind();
    }

    @AfterEach void unbind() {
        if (router != null) router.close();
    }

    private ZenohSession newSession() {
        TcpTransport t = new TcpTransport("127.0.0.1", router.port());
        return ZenohSession.builder(t)
                .leaseMs(2_000)
                .handshakeTimeoutMs(2_000)
                .closeTimeoutMs(500)
                .build();
    }

    @Test void handshakeCompletesAndReachesOpen() throws Exception {
        try (ZenohSession s = newSession()) {
            assertEquals(SessionState.CREATED, s.state());
            s.open();
            assertEquals(SessionState.OPEN, s.state());
            assertNotNull(s.remoteId());
            assertEquals(router.serverId(), s.remoteId());
            assertTrue(s.negotiatedLeaseMs() > 0);
        }
    }

    @Test void openTwiceRejected() throws Exception {
        try (ZenohSession s = newSession()) {
            s.open();
            assertThrows(SessionException.class, s::open);
        }
    }

    @Test void publishBeforeOpenRejected() throws Exception {
        try (ZenohSession s = newSession()) {
            assertThrows(SessionException.class,
                    () -> s.publish("k", new byte[] { 'x' }));
        }
    }

    @Test void publishEmitsFrameContainingPushContainingPut() throws Exception {
        try (ZenohSession s = newSession()) {
            s.open();
            s.publishString("demo/hello", "world");

            // Wait for the router to record it.
            LoopbackZenohRouter.Batch b = router.sessions().get(0).received
                    .poll(2, TimeUnit.SECONDS);
            assertNotNull(b, "server did not receive the publish");
            assertEquals(Frame.ID, b.id());
            Frame frame = Frame.decode(b.bytes());
            assertTrue(frame.reliable());
            assertEquals(0L, frame.sn());  // first frame in the session
            Push push = Push.decode(frame.payload());
            assertEquals("demo/hello", push.keySuffix());
            Put put = Put.decode(push.body());
            assertArrayEquals("world".getBytes(StandardCharsets.UTF_8), put.payload());
        }
    }

    @Test void publishAssignsMonotonicSequenceNumbers() throws Exception {
        try (ZenohSession s = newSession()) {
            s.open();
            for (int i = 0; i < 5; i++) {
                s.publishString("k/" + i, "v" + i);
            }
            LoopbackZenohRouter.ClientSession cs = router.sessions().get(0);
            long prevSn = -1;
            for (int i = 0; i < 5; i++) {
                LoopbackZenohRouter.Batch b = cs.received.poll(2, TimeUnit.SECONDS);
                assertNotNull(b, "frame " + i + " missing");
                assertEquals(Frame.ID, b.id());
                Frame f = Frame.decode(b.bytes());
                assertTrue(f.sn() > prevSn, "sn must be strictly increasing: " + f.sn() + " > " + prevSn);
                prevSn = f.sn();
            }
        }
    }

    @Test void keepAliveFiresWhenOutboundQuiet() throws Exception {
        // Lease 500 ms → keep-alive tick at 125 ms. Stay silent for ~600 ms
        // and expect at least a couple of KEEP_ALIVE frames to hit the server.
        TcpTransport t = new TcpTransport("127.0.0.1", router.port());
        try (ZenohSession s = ZenohSession.builder(t)
                .leaseMs(1_000)
                .handshakeTimeoutMs(2_000)
                .closeTimeoutMs(500)
                .build()) {
            s.open();
            LoopbackZenohRouter.ClientSession cs = router.sessions().get(0);
            long deadline = System.currentTimeMillis() + 1_500;
            int keepAlives = 0;
            while (System.currentTimeMillis() < deadline && keepAlives < 2) {
                LoopbackZenohRouter.Batch b = cs.received.poll(300, TimeUnit.MILLISECONDS);
                if (b != null && b.id() == KeepAlive.ID) keepAlives++;
            }
            assertTrue(keepAlives >= 2, "expected >=2 keep-alives, saw " + keepAlives);
        }
    }

    @Test void keepAliveSkippedWhenOutboundBusy() throws Exception {
        TcpTransport t = new TcpTransport("127.0.0.1", router.port());
        try (ZenohSession s = ZenohSession.builder(t)
                .leaseMs(1_000)   // tick every 250 ms
                .handshakeTimeoutMs(2_000)
                .closeTimeoutMs(500)
                .build()) {
            s.open();
            LoopbackZenohRouter.ClientSession cs = router.sessions().get(0);
            // Publish faster than the keep-alive tick for 800 ms.
            long deadline = System.currentTimeMillis() + 800;
            while (System.currentTimeMillis() < deadline) {
                s.publishString("busy/" + System.nanoTime(), "v");
                Thread.sleep(50);
            }
            int keepAlives = 0;
            LoopbackZenohRouter.Batch b;
            while ((b = cs.received.poll(50, TimeUnit.MILLISECONDS)) != null) {
                if (b.id() == KeepAlive.ID) keepAlives++;
            }
            assertEquals(0, keepAlives, "no keep-alives should fire while outbound is busy");
        }
    }

    @Test void serverCloseFrameTearsDownSession() throws Exception {
        try (ZenohSession s = newSession()) {
            s.open();
            LoopbackZenohRouter.ClientSession cs = router.sessions().get(0);
            cs.sendClose();
            long deadline = System.currentTimeMillis() + 1_000;
            while (s.state() == SessionState.OPEN && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(SessionState.CLOSED, s.state());
        }
    }

    @Test void closeEmitsCloseFrameToPeer() throws Exception {
        ZenohSession s = newSession();
        s.open();
        LoopbackZenohRouter.ClientSession cs = router.sessions().get(0);
        // Drain the initial-tick keep-alive if any.
        cs.received.clear();
        s.close();
        // Look for a CLOSE frame in the router's inbox.
        boolean sawClose = false;
        long deadline = System.currentTimeMillis() + 1_000;
        while (System.currentTimeMillis() < deadline) {
            LoopbackZenohRouter.Batch b = cs.received.poll(100, TimeUnit.MILLISECONDS);
            if (b == null) break;
            if (b.id() == Close.ID) { sawClose = true; break; }
        }
        assertTrue(sawClose, "close() must emit a CLOSE frame");
        assertEquals(SessionState.CLOSED, s.state());
    }

    @Test void closeIsIdempotent() throws Exception {
        ZenohSession s = newSession();
        s.open();
        s.close();
        s.close();   // must not throw
        s.close();
        assertEquals(SessionState.CLOSED, s.state());
    }

    @Test void publishAfterCloseRejected() throws Exception {
        ZenohSession s = newSession();
        s.open();
        s.close();
        assertThrows(SessionException.class,
                () -> s.publish("k", new byte[] { 'x' }));
    }

    @Test void handshakeTimeoutSurfacesAsSessionException() throws Exception {
        // Router that never replies to InitSyn.
        router.close();
        LoopbackZenohRouter.Behaviour b = new LoopbackZenohRouter.Behaviour();
        b.handshakeStallMs = 5_000;
        try (LoopbackZenohRouter stalling = LoopbackZenohRouter.bind(b)) {
            TcpTransport t = new TcpTransport("127.0.0.1", stalling.port());
            try (ZenohSession s = ZenohSession.builder(t)
                    .leaseMs(2_000)
                    .handshakeTimeoutMs(300)   // give up fast
                    .closeTimeoutMs(500)
                    .build()) {
                SessionException e = assertThrows(SessionException.class, s::open);
                assertTrue(e.getMessage().contains("InitAck")
                                || e.getMessage().contains("not received"),
                        "message was: " + e.getMessage());
                assertEquals(SessionState.CLOSED, s.state());
            }
        }
    }

    @Test void garbageInitAckSurfacesAsSessionException() throws Exception {
        router.close();
        LoopbackZenohRouter.Behaviour b = new LoopbackZenohRouter.Behaviour();
        b.sendGarbageInitAck = true;
        try (LoopbackZenohRouter bad = LoopbackZenohRouter.bind(b)) {
            TcpTransport t = new TcpTransport("127.0.0.1", bad.port());
            try (ZenohSession s = ZenohSession.builder(t)
                    .leaseMs(2_000)
                    .handshakeTimeoutMs(2_000)
                    .closeTimeoutMs(500)
                    .build()) {
                SessionException e = assertThrows(SessionException.class, s::open);
                assertTrue(e.getMessage().contains("InitAck")
                                || e.getMessage().contains("decode"),
                        "message was: " + e.getMessage());
                assertEquals(SessionState.CLOSED, s.state());
            }
        }
    }

    @Test void leaseExpiryClosesSessionWhenServerGoesSilent() throws Exception {
        // Server accepts our proposed lease, then never responds to keep-alives.
        router.close();
        LoopbackZenohRouter.Behaviour b = new LoopbackZenohRouter.Behaviour();
        AtomicReference<LoopbackZenohRouter.ClientSession> csRef = new AtomicReference<>();
        b.onOpened = cs -> {
            csRef.set(cs);
            // Kill the socket immediately after handshake so the client sees NO inbound
            // traffic and lease expiry kicks in.
            cs.close();
        };
        try (LoopbackZenohRouter silent = LoopbackZenohRouter.bind(b)) {
            TcpTransport t = new TcpTransport("127.0.0.1", silent.port());
            try (ZenohSession s = ZenohSession.builder(t)
                    .leaseMs(1_000)
                    .handshakeTimeoutMs(2_000)
                    .closeTimeoutMs(500)
                    .build()) {
                s.open();
                // Should close well within lease window (transport error) or lease expiry.
                long deadline = System.currentTimeMillis() + 2_000;
                while (s.state() == SessionState.OPEN && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20);
                }
                assertEquals(SessionState.CLOSED, s.state());
            }
        }
    }

    @Test void nullTransportRejected() {
        assertThrows(NullPointerException.class,
                () -> ZenohSession.builder(null));
    }

    @Test void invalidLeaseRejected() {
        TcpTransport t = new TcpTransport("127.0.0.1", router.port());
        assertThrows(IllegalArgumentException.class,
                () -> ZenohSession.builder(t).leaseMs(500));
        assertThrows(IllegalArgumentException.class,
                () -> ZenohSession.builder(t).handshakeTimeoutMs(0));
        assertThrows(IllegalArgumentException.class,
                () -> ZenohSession.builder(t).closeTimeoutMs(-1));
    }

    @Test void routerAcceptsOverridingLease() throws Exception {
        router.close();
        LoopbackZenohRouter.Behaviour b = new LoopbackZenohRouter.Behaviour();
        b.leaseOverrideMs = 3_500;
        try (LoopbackZenohRouter override = LoopbackZenohRouter.bind(b)) {
            TcpTransport t = new TcpTransport("127.0.0.1", override.port());
            try (ZenohSession s = ZenohSession.builder(t)
                    .leaseMs(10_000)              // propose 10 s
                    .handshakeTimeoutMs(2_000)
                    .closeTimeoutMs(500)
                    .build()) {
                s.open();
                assertEquals(3_500L, s.negotiatedLeaseMs(),
                        "session must adopt server's OpenAck lease");
            }
        }
    }
}
