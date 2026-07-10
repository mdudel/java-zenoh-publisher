/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava;

import io.mdudel.zenoh.purejava.session.LoopbackZenohRouter;
import io.mdudel.zenoh.purejava.transport.TcpTransport;
import io.mdudel.zenoh.purejava.transport.TlsTransport;
import io.mdudel.zenoh.purejava.transport.Transport;
import io.mdudel.zenoh.purejava.transport.WsTransport;
import io.mdudel.zenoh.purejava.wire.messages.Frame;
import io.mdudel.zenoh.purejava.wire.messages.Push;
import io.mdudel.zenoh.purejava.wire.messages.Put;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the top-level {@link PureJavaZenohPublisher} facade.
 * Uses the {@link LoopbackZenohRouter} from Turn D so we exercise the full
 * publisher &rarr; session &rarr; transport &rarr; wire path.
 */
class PureJavaZenohPublisherTest {

    private LoopbackZenohRouter router;

    @BeforeEach void bind() throws IOException {
        router = LoopbackZenohRouter.bind();
    }

    @AfterEach void unbind() {
        if (router != null) router.close();
    }

    @Test void startPublishStopRoundtripsThroughRouter() throws Exception {
        try (PureJavaZenohPublisher pub = PureJavaZenohPublisher.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .keyExpr("demo/hello")
                .leaseMs(2_000)
                .build()) {

            assertFalse(pub.isActive());
            pub.start();
            assertTrue(pub.isActive());

            pub.publishString(null, "hello world");

            LoopbackZenohRouter.Batch b = router.sessions().get(0).received
                    .poll(2, TimeUnit.SECONDS);
            assertNotNull(b, "server did not receive the publish");
            assertEquals(Frame.ID, b.id());
            Frame frame = Frame.decode(b.bytes());
            Push push = Push.decode(frame.payload());
            assertEquals("demo/hello", push.keySuffix());
            Put put = Put.decode(push.body());
            assertArrayEquals("hello world".getBytes(StandardCharsets.UTF_8), put.payload());

            assertEquals(1L, pub.getSentCount());
            assertTrue(pub.getLastSendMs() > 0);
        }
    }

    @Test void orgPrefixAppliedToEffectiveKey() throws Exception {
        try (PureJavaZenohPublisher pub = PureJavaZenohPublisher.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .keyExpr("thing")
                .org("acme/dev")
                .leaseMs(2_000)
                .build()) {
            assertEquals("acme/dev/thing", pub.getEffectiveKeyExpr());
            pub.start();
            pub.publish("v1".getBytes(StandardCharsets.UTF_8));
            LoopbackZenohRouter.Batch b = router.sessions().get(0).received
                    .poll(2, TimeUnit.SECONDS);
            assertNotNull(b);
            Frame frame = Frame.decode(b.bytes());
            Push push = Push.decode(frame.payload());
            assertEquals("acme/dev/thing", push.keySuffix());
        }
    }

    @Test void subKeyAppendsUnderneathEffectiveKey() throws Exception {
        try (PureJavaZenohPublisher pub = PureJavaZenohPublisher.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .keyExpr("tracks")
                .leaseMs(2_000)
                .build()) {
            pub.start();
            pub.publish("track-42", "payload".getBytes(StandardCharsets.UTF_8));
            LoopbackZenohRouter.Batch b = router.sessions().get(0).received
                    .poll(2, TimeUnit.SECONDS);
            assertNotNull(b);
            Frame frame = Frame.decode(b.bytes());
            Push push = Push.decode(frame.payload());
            assertEquals("tracks/track-42", push.keySuffix());
        }
    }

    @Test void publishBeforeStartRejected() throws Exception {
        try (PureJavaZenohPublisher pub = PureJavaZenohPublisher.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .leaseMs(2_000)
                .build()) {
            assertThrows(IOException.class,
                    () -> pub.publish("x".getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test void publishAfterCloseRejected() throws Exception {
        PureJavaZenohPublisher pub = PureJavaZenohPublisher.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .leaseMs(2_000)
                .build();
        pub.start();
        pub.close();
        assertThrows(IOException.class,
                () -> pub.publish("x".getBytes(StandardCharsets.UTF_8)));
    }

    @Test void doubleStartIdempotent() throws Exception {
        try (PureJavaZenohPublisher pub = PureJavaZenohPublisher.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .leaseMs(2_000)
                .build()) {
            pub.start();
            pub.start();   // must not throw or reconnect
            assertTrue(pub.isActive());
        }
    }

    @Test void closeIdempotent() throws Exception {
        PureJavaZenohPublisher pub = PureJavaZenohPublisher.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .leaseMs(2_000)
                .build();
        pub.start();
        pub.close();
        pub.close();
        pub.close();
        assertFalse(pub.isActive());
    }

    @Test void emptyEndpointRejectedAtStart() {
        PureJavaZenohPublisher pub = PureJavaZenohPublisher.builder()
                .connectEndpoint("")
                .build();
        assertThrows(IOException.class, pub::start);
    }

    @Test void unsupportedSchemeRejectedAtStart() {
        PureJavaZenohPublisher pub = PureJavaZenohPublisher.builder()
                .connectEndpoint("udp/127.0.0.1:9000")
                .build();
        IOException e = assertThrows(IOException.class, pub::start);
        assertTrue(e.getMessage().contains("unsupported endpoint scheme"),
                "message was: " + e.getMessage());
    }

    @Test void malformedEndpointRejectedAtStart() {
        PureJavaZenohPublisher pub = PureJavaZenohPublisher.builder()
                .connectEndpoint("tcp127.0.0.1:7447")  // missing slash
                .build();
        assertThrows(IOException.class, pub::start);
    }

    @Test void invalidPortRejectedAtStart() {
        PureJavaZenohPublisher pub = PureJavaZenohPublisher.builder()
                .connectEndpoint("tcp/127.0.0.1:not-a-port")
                .build();
        assertThrows(IOException.class, pub::start);
    }

    @Test void buildTransportPicksTcpForTcpScheme() throws Exception {
        PureJavaZenohPublisher pub = PureJavaZenohPublisher.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .build();
        Transport t = pub.buildTransport();
        try {
            assertTrue(t instanceof TcpTransport);
            assertEquals("tcp/127.0.0.1:" + router.port(), t.describe());
        } finally { t.close(); }
    }

    @Test void buildTransportPicksWsForWsScheme() throws Exception {
        PureJavaZenohPublisher pub = PureJavaZenohPublisher.builder()
                .connectEndpoint("ws/127.0.0.1:9001")
                .build();
        Transport t = pub.buildTransport();
        try {
            assertTrue(t instanceof WsTransport,
                    "expected WsTransport, got: " + t.getClass().getSimpleName());
            assertEquals("ws/127.0.0.1:9001", t.describe());
        } finally { t.close(); }
    }

    @Test void buildTransportPicksWsForFullWsUri() throws Exception {
        PureJavaZenohPublisher pub = PureJavaZenohPublisher.builder()
                .connectEndpoint("ws://127.0.0.1:9002/zenoh")
                .build();
        Transport t = pub.buildTransport();
        try {
            assertTrue(t instanceof WsTransport);
            assertTrue(t.describe().contains("/zenoh"),
                    "describe should include path: " + t.describe());
        } finally { t.close(); }
    }

    @Test void buildTransportPicksTlsForTlsScheme() throws Exception {
        PureJavaZenohPublisher pub = PureJavaZenohPublisher.builder()
                .connectEndpoint("tls/127.0.0.1:9000")
                .verifyHostname(false)
                .build();
        Transport t = pub.buildTransport();
        try {
            assertTrue(t instanceof TlsTransport);
        } finally { t.close(); }
    }

    @Test void cliMainDoesNotThrowOnValidRouter() throws Exception {
        // Run PureJavaZenohPublisher.main with our loopback endpoint.
        String[] args = {
                "tcp/127.0.0.1:" + router.port(),
                "cli/smoke",
                "hi from cli"
        };
        PureJavaZenohPublisher.main(args);
        // Assert the router received a publish carrying our CLI payload.
        LoopbackZenohRouter.Batch b = router.sessions().get(0).received
                .poll(2, TimeUnit.SECONDS);
        assertNotNull(b, "CLI main should have published");
        Frame frame = Frame.decode(b.bytes());
        Push push = Push.decode(frame.payload());
        assertEquals("cli/smoke", push.keySuffix());
        Put put = Put.decode(push.body());
        assertArrayEquals("hi from cli".getBytes(StandardCharsets.UTF_8), put.payload());
    }
}
