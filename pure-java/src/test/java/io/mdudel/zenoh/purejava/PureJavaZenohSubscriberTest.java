/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava;

import io.mdudel.zenoh.purejava.session.LoopbackZenohRouter;
import io.mdudel.zenoh.purejava.session.Sample;
import io.mdudel.zenoh.purejava.session.Subscription;
import io.mdudel.zenoh.purejava.transport.TcpTransport;
import io.mdudel.zenoh.purejava.transport.TlsTransport;
import io.mdudel.zenoh.purejava.transport.Transport;
import io.mdudel.zenoh.purejava.transport.WsTransport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PureJavaZenohSubscriberTest {

    private LoopbackZenohRouter router;

    @BeforeEach void bind() throws IOException {
        router = LoopbackZenohRouter.bind();
    }

    @AfterEach void unbind() {
        if (router != null) router.close();
    }

    @Test void startSubscribeReceiveRoundtripThroughRouter() throws Exception {
        try (PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .leaseMs(2_000)
                .build()) {

            assertFalse(sub.isActive());
            sub.start();
            assertTrue(sub.isActive());

            Subscription subscription = sub.subscribe("demo/**");
            // Give the DECLARE a moment to reach the router.
            Thread.sleep(100);

            LoopbackZenohRouter.ClientSession peer = router.sessions().get(0);
            peer.sendPushString(1L, "demo/hello", "world");

            Sample got = subscription.poll(2, TimeUnit.SECONDS);
            assertNotNull(got, "subscriber did not receive the message");
            assertEquals("demo/hello", got.key());
            assertEquals("world", got.payloadAsString());
            assertEquals(1L, sub.getReceivedCount());
        }
    }

    @Test void subscribeAndConsumeDeliversViaCallback() throws Exception {
        try (PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .leaseMs(2_000)
                .build()) {

            sub.start();
            CopyOnWriteArrayList<String> got = new CopyOnWriteArrayList<>();
            CountDownLatch received = new CountDownLatch(2);
            sub.subscribeAndConsume("greetings/**", sample -> {
                got.add(sample.key() + "=" + sample.payloadAsString());
                received.countDown();
            });
            Thread.sleep(100);   // DECLARE settle

            LoopbackZenohRouter.ClientSession peer = router.sessions().get(0);
            peer.sendPushString(1L, "greetings/en", "hi");
            peer.sendPushString(2L, "greetings/de", "hallo");

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(2, got.size());
            assertTrue(got.contains("greetings/en=hi"));
            assertTrue(got.contains("greetings/de=hallo"));
        }
    }

    @Test void orgPrefixAppliedToEffectiveKey() throws Exception {
        try (PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .org("acme/dev")
                .leaseMs(2_000)
                .build()) {

            sub.start();
            Subscription subscription = sub.subscribe("thing/**");
            assertEquals("acme/dev/thing/**", subscription.keyExpr().value());
        }
    }

    @Test void multipleSubscriptionsShareTheSameSession() throws Exception {
        try (PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .leaseMs(2_000)
                .build()) {

            sub.start();
            Subscription a = sub.subscribe("a/**");
            Subscription b = sub.subscribe("b/**");
            Thread.sleep(150);

            LoopbackZenohRouter.ClientSession peer = router.sessions().get(0);
            peer.sendPushString(1L, "a/x", "for-a");
            peer.sendPushString(2L, "b/y", "for-b");

            Sample sa = a.poll(2, TimeUnit.SECONDS);
            Sample sb = b.poll(2, TimeUnit.SECONDS);
            assertNotNull(sa);
            assertNotNull(sb);
            assertEquals("for-a", sa.payloadAsString());
            assertEquals("for-b", sb.payloadAsString());
            // Each subscription got exactly one; total is 2.
            assertEquals(2L, sub.getReceivedCount());
        }
    }

    @Test void subscribeBeforeStartRejected() throws Exception {
        try (PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .leaseMs(2_000)
                .build()) {
            assertThrows(IOException.class, () -> sub.subscribe("demo/**"));
        }
    }

    @Test void closeTearsDownAllSubscriptions() throws Exception {
        PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .leaseMs(2_000)
                .build();
        sub.start();
        Subscription a = sub.subscribe("a/**");
        Subscription b = sub.subscribe("b/**");
        sub.close();
        assertFalse(a.isOpen());
        assertFalse(b.isOpen());
        assertFalse(sub.isActive());
    }

    @Test void doubleStartIdempotent() throws Exception {
        try (PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .leaseMs(2_000)
                .build()) {
            sub.start();
            sub.start();   // no throw
            assertTrue(sub.isActive());
        }
    }

    @Test void closeIdempotent() throws Exception {
        PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .leaseMs(2_000)
                .build();
        sub.start();
        sub.close();
        sub.close();
        sub.close();
        assertFalse(sub.isActive());
    }

    @Test void emptyEndpointRejectedAtStart() {
        PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint("")
                .build();
        assertThrows(IOException.class, sub::start);
    }

    @Test void unsupportedSchemeRejectedAtStart() {
        PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint("udp/127.0.0.1:9000")
                .build();
        IOException e = assertThrows(IOException.class, sub::start);
        assertTrue(e.getMessage().contains("unsupported endpoint scheme"),
                "message was: " + e.getMessage());
    }

    @Test void malformedEndpointRejectedAtStart() {
        PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint("tcp127.0.0.1:7447")
                .build();
        assertThrows(IOException.class, sub::start);
    }

    @Test void buildTransportPicksTcpForTcpScheme() throws Exception {
        PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .build();
        Transport t = sub.buildTransport();
        try {
            assertTrue(t instanceof TcpTransport);
            assertEquals("tcp/127.0.0.1:" + router.port(), t.describe());
        } finally { t.close(); }
    }

    @Test void buildTransportPicksWsForWsScheme() throws Exception {
        PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint("ws/127.0.0.1:9001")
                .build();
        Transport t = sub.buildTransport();
        try {
            assertTrue(t instanceof WsTransport);
        } finally { t.close(); }
    }

    @Test void buildTransportPicksTlsForTlsScheme() throws Exception {
        PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint("tls/127.0.0.1:9000")
                .verifyHostname(false)
                .build();
        Transport t = sub.buildTransport();
        try {
            assertTrue(t instanceof TlsTransport);
        } finally { t.close(); }
    }

    @Test void publishAndSubscribeInterop() throws Exception {
        // Sanity: PureJavaZenohPublisher + PureJavaZenohSubscriber both work
        // against the same loopback router at the same time. The loopback
        // router doesn't route publishes to subscribers automatically -- it's
        // just a wire probe -- so we assert on the router-side received queue
        // rather than the subscriber inbox.
        try (PureJavaZenohPublisher pub = PureJavaZenohPublisher.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .keyExpr("interop/hello")
                .leaseMs(2_000)
                .build();
             PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .leaseMs(2_000)
                .build()) {

            pub.start();
            sub.start();
            Subscription subscription = sub.subscribe("interop/**");
            Thread.sleep(100);

            pub.publishString(null, "hi");

            // Two sessions expected on the router.
            long deadline = System.currentTimeMillis() + 1_000;
            while (router.sessions().size() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(2, router.sessions().size(),
                    "expected two client sessions: one publisher + one subscriber");
            // We don't assert subscription delivery because the loopback router
            // doesn't fan out publishes to subscribers. Real interop test is
            // Chunk 9 of mtls-smoke-test (against zenohd).
            assertNull(subscription.poll(200, TimeUnit.MILLISECONDS),
                    "loopback router doesn't route publisher->subscriber; a real "
                            + "sample here would mean the router mock grew fan-out "
                            + "and this assumption is stale");
        }
    }
}
