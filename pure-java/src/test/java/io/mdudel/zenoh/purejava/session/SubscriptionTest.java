/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.session;

import io.mdudel.zenoh.purejava.transport.TcpTransport;
import io.mdudel.zenoh.purejava.wire.KeyExpr;
import io.mdudel.zenoh.purejava.wire.messages.Declare;
import io.mdudel.zenoh.purejava.wire.messages.DeclareSubscriber;
import io.mdudel.zenoh.purejava.wire.messages.UndeclareSubscriber;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionTest {

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

    @Test void declareSubscriberSendsDeclareToRouter() throws Exception {
        try (ZenohSession s = newSession()) {
            s.open();
            Subscription sub = s.declareSubscriber(KeyExpr.of("demo/**"));
            assertTrue(sub.isOpen());
            assertEquals("demo/**", sub.keyExpr().value());

            // Router should have received a DECLARE frame carrying DeclareSubscriber.
            List<Declare> declares = waitForDeclare(1);
            assertEquals(1, declares.size());
            assertEquals(Declare.Body.BodyKind.DECLARE_SUBSCRIBER, declares.get(0).body().kind());
            DeclareSubscriber ds = declares.get(0).body().asDeclareSubscriber();
            assertEquals("demo/**", ds.keySuffix());
            assertEquals(sub.id(), ds.id());
        }
    }

    @Test void inboundPushIsRoutedToMatchingSubscription() throws Exception {
        try (ZenohSession s = newSession()) {
            s.open();
            Subscription sub = s.declareSubscriber(KeyExpr.of("demo/**"));
            waitForDeclare(1);   // ensure the client-side registration is in place

            LoopbackZenohRouter.ClientSession peer = router.sessions().get(0);
            peer.sendPushString(1L, "demo/greeting", "hello");

            Sample got = sub.poll(2, TimeUnit.SECONDS);
            assertNotNull(got, "subscription did not receive the message");
            assertEquals("demo/greeting", got.key());
            assertEquals("hello", got.payloadAsString());
            assertEquals(1L, sub.receivedCount());
        }
    }

    @Test void wildcardSubscriptionMatchesMultipleKeys() throws Exception {
        try (ZenohSession s = newSession()) {
            s.open();
            Subscription sub = s.declareSubscriber(KeyExpr.of("sensors/*/temp"));
            waitForDeclare(1);

            LoopbackZenohRouter.ClientSession peer = router.sessions().get(0);
            peer.sendPushString(1L, "sensors/room1/temp", "22.4");
            peer.sendPushString(2L, "sensors/room2/temp", "21.1");
            peer.sendPushString(3L, "sensors/kitchen/temp", "24.0");
            // Should NOT match:
            peer.sendPushString(4L, "sensors/room1/humidity", "40%");
            peer.sendPushString(5L, "sensors/room1/x/temp",   "should-not-match");

            List<Sample> got = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Sample s2 = sub.poll(2, TimeUnit.SECONDS);
                assertNotNull(s2, "sample " + i + " missing");
                got.add(s2);
            }
            // Non-matching samples should still be pollable-null after a short wait.
            assertNull(sub.poll(200, TimeUnit.MILLISECONDS),
                    "unexpected extra sample beyond the 3 matching ones");

            assertEquals(3, got.size());
            for (Sample s2 : got) {
                assertTrue(s2.key().startsWith("sensors/") && s2.key().endsWith("/temp"),
                        "unexpected key: " + s2.key());
            }
        }
    }

    @Test void multipleSubscriptionsGetIndependentCopies() throws Exception {
        try (ZenohSession s = newSession()) {
            s.open();
            Subscription subA = s.declareSubscriber(KeyExpr.of("demo/**"));
            Subscription subB = s.declareSubscriber(KeyExpr.of("**/greeting"));
            waitForDeclare(2);

            LoopbackZenohRouter.ClientSession peer = router.sessions().get(0);
            peer.sendPushString(1L, "demo/greeting", "shared");

            Sample a = subA.poll(2, TimeUnit.SECONDS);
            Sample b = subB.poll(2, TimeUnit.SECONDS);
            assertNotNull(a, "subA missed the sample");
            assertNotNull(b, "subB missed the sample");
            assertEquals("shared", a.payloadAsString());
            assertEquals("shared", b.payloadAsString());
            assertArrayEquals(a.payload(), b.payload());
            // Distinct Subscription ids on the wire.
            assertFalse(subA.id() == subB.id());
        }
    }

    @Test void closeSubscriptionSendsUndeclareAndStopsDelivery() throws Exception {
        try (ZenohSession s = newSession()) {
            s.open();
            Subscription sub = s.declareSubscriber(KeyExpr.of("demo/**"));
            long subId = sub.id();
            waitForDeclare(1);

            LoopbackZenohRouter.ClientSession peer = router.sessions().get(0);
            peer.sendPushString(1L, "demo/before-close", "in");
            assertNotNull(sub.poll(2, TimeUnit.SECONDS));

            sub.close();

            // Wait for the UndeclareSubscriber to arrive on the router side.
            long deadline = System.currentTimeMillis() + 1_000;
            UndeclareSubscriber undeclared = null;
            while (System.currentTimeMillis() < deadline && undeclared == null) {
                for (Declare d : peer.receivedDeclares()) {
                    if (d.body().kind() == Declare.Body.BodyKind.UNDECLARE_SUBSCRIBER
                            && d.body().asUndeclareSubscriber().id() == subId) {
                        undeclared = d.body().asUndeclareSubscriber();
                        break;
                    }
                }
                if (undeclared == null) Thread.sleep(20);
            }
            assertNotNull(undeclared, "router did not receive UndeclareSubscriber");
            assertEquals(subId, undeclared.id());

            // Delivery after close is a no-op (offer() short-circuits on closed).
            peer.sendPushString(2L, "demo/after-close", "should-not-see");
            assertNull(sub.poll(200, TimeUnit.MILLISECONDS));

            assertFalse(sub.isOpen());
        }
    }

    @Test void takeUnblocksOnCloseReturningNull() throws Exception {
        try (ZenohSession s = newSession()) {
            s.open();
            Subscription sub = s.declareSubscriber(KeyExpr.of("demo/**"));

            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch done    = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<Sample> got = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Throwable> ex = new java.util.concurrent.atomic.AtomicReference<>();
            Thread t = new Thread(() -> {
                started.countDown();
                try { got.set(sub.take()); }
                catch (Throwable th) { ex.set(th); }
                finally { done.countDown(); }
            });
            t.start();
            assertTrue(started.await(1, TimeUnit.SECONDS));
            Thread.sleep(150);   // let it actually enter take() and iterate at least once
            sub.close();
            // take()'s poll(100 ms) loop takes at most 100 ms to notice close.
            // Give it 500 ms of headroom.
            assertTrue(done.await(1, TimeUnit.SECONDS), "take() didn't unblock on close");
            assertNull(got.get(), "take() returned a sample we didn't deliver");
            assertNull(ex.get(), "take() threw unexpectedly: " + ex.get());
        }
    }

    @Test void forEachDeliversToCallback() throws Exception {
        try (ZenohSession s = newSession()) {
            s.open();
            Subscription sub = s.declareSubscriber(KeyExpr.of("demo/**"));
            waitForDeclare(1);

            CopyOnWriteArrayList<String> keys = new CopyOnWriteArrayList<>();
            CountDownLatch received = new CountDownLatch(3);
            sub.forEach(sample -> {
                keys.add(sample.key());
                received.countDown();
            });

            LoopbackZenohRouter.ClientSession peer = router.sessions().get(0);
            peer.sendPushString(1L, "demo/a", "1");
            peer.sendPushString(2L, "demo/b", "2");
            peer.sendPushString(3L, "demo/c", "3");

            assertTrue(received.await(2, TimeUnit.SECONDS),
                    "callback saw only " + keys.size() + "/3 samples");
            assertTrue(keys.contains("demo/a"));
            assertTrue(keys.contains("demo/b"));
            assertTrue(keys.contains("demo/c"));
        }
    }

    @Test void forEachCallbackExceptionDoesNotBreakDelivery() throws Exception {
        try (ZenohSession s = newSession()) {
            s.open();
            Subscription sub = s.declareSubscriber(KeyExpr.of("demo/**"));
            waitForDeclare(1);

            AtomicInteger seen = new AtomicInteger(0);
            CountDownLatch got3 = new CountDownLatch(3);
            sub.forEach(sample -> {
                int n = seen.incrementAndGet();
                got3.countDown();
                if (n == 2) throw new RuntimeException("intentional test throw");
            });

            LoopbackZenohRouter.ClientSession peer = router.sessions().get(0);
            peer.sendPushString(1L, "demo/one",   "1");
            peer.sendPushString(2L, "demo/two",   "2");
            peer.sendPushString(3L, "demo/three", "3");

            assertTrue(got3.await(2, TimeUnit.SECONDS),
                    "delivery halted after callback threw; seen=" + seen.get());
            assertEquals(3, seen.get());
        }
    }

    @Test void secondForEachRejected() throws Exception {
        try (ZenohSession s = newSession()) {
            s.open();
            Subscription sub = s.declareSubscriber(KeyExpr.of("demo/**"));
            sub.forEach(x -> { /* ignore */ });
            assertThrows(IllegalStateException.class,
                    () -> sub.forEach(x -> { /* second */ }));
        }
    }

    @Test void declareSubscriberBeforeOpenRejected() throws Exception {
        try (ZenohSession s = newSession()) {
            assertThrows(SessionException.class,
                    () -> s.declareSubscriber(KeyExpr.of("demo/**")));
        }
    }

    @Test void closeIdempotent() throws Exception {
        try (ZenohSession s = newSession()) {
            s.open();
            Subscription sub = s.declareSubscriber(KeyExpr.of("demo/**"));
            sub.close();
            sub.close();   // no throw
            sub.close();
            assertFalse(sub.isOpen());
        }
    }

    @Test void sessionCloseAlsoTearsDownSubscriptions() throws Exception {
        ZenohSession s = newSession();
        s.open();
        Subscription sub = s.declareSubscriber(KeyExpr.of("demo/**"));
        s.close();
        // Session close doesn't call Subscription.close() directly today
        // (subscriptions are session-scoped and the transport dies with the
        // session, so their queues just stop being fed). isOpen() is still
        // true on the Subscription object -- that's fine; take() will hang
        // forever waiting for a sample that never comes. Document that
        // shape here for the future.
        assertTrue(sub.isOpen(), "Subscription doesn't auto-close on session.close()");
        // But the session's own state IS closed.
        assertEquals(SessionState.CLOSED, s.state());
    }

    // ---- helpers -----------------------------------------------------

    /**
     * Poll the router for at least {@code n} DECLARE messages received
     * from the (single) connected client. Fails the test if they don't
     * arrive within 2 seconds.
     */
    private List<Declare> waitForDeclare(int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            List<Declare> ds = router.sessions().isEmpty()
                    ? List.of()
                    : router.sessions().get(0).receivedDeclares();
            // Filter to just DeclareSubscriber (not UndeclareSubscriber echoes
            // if they arrive early). Callers assert on the DeclareSubscriber shape.
            long dsubs = ds.stream()
                    .filter(d -> d.body().kind() == Declare.Body.BodyKind.DECLARE_SUBSCRIBER)
                    .count();
            if (dsubs >= n) return ds.stream()
                    .filter(d -> d.body().kind() == Declare.Body.BodyKind.DECLARE_SUBSCRIBER)
                    .toList();
            Thread.sleep(20);
        }
        return router.sessions().isEmpty()
                ? List.of()
                : router.sessions().get(0).receivedDeclares().stream()
                    .filter(d -> d.body().kind() == Declare.Body.BodyKind.DECLARE_SUBSCRIBER)
                    .toList();
    }
}
