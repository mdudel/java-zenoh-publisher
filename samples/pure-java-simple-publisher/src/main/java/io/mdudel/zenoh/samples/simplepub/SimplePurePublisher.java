/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.samples.simplepub;

import io.mdudel.zenoh.purejava.PureJavaZenohPublisher;

import java.nio.charset.StandardCharsets;

/**
 * Absolute minimum viable pure-Java Zenoh publisher.
 *
 * <p>Opens a session to a router, publishes N payloads at a
 * configurable interval, closes. No JNI, no native binaries, no
 * third-party runtime dependencies.</p>
 *
 * <p>Positional args (all optional):</p>
 * <pre>
 * java -jar target/pure-java-simple-publisher-0.1.0.jar
 *   [endpoint]   default: tcp/localhost:7447
 *   [key]        default: demo/greeting
 *   [count]      default: 5   (number of messages)
 *   [intervalMs] default: 1000 (interval between messages)
 * </pre>
 *
 * <p>Verify on the subscriber side, e.g.
 * {@code z_sub -k 'demo/greeting'}, or the {@code hello-subscriber}
 * sample in the sibling folder.</p>
 */
public final class SimplePurePublisher {

    public static void main(String[] args) throws Exception {
        String endpoint = args.length > 0 ? args[0] : "tcp/localhost:7447";
        String key      = args.length > 1 ? args[1] : "demo/greeting";
        int    count    = args.length > 2 ? Integer.parseInt(args[2]) : 5;
        long   interval = args.length > 3 ? Long.parseLong(args[3])   : 1_000L;

        System.out.println("[pure-java-simple-publisher] endpoint=" + endpoint
                + " key=" + key + " count=" + count + " intervalMs=" + interval);

        try (PureJavaZenohPublisher pub = PureJavaZenohPublisher.builder()
                .connectEndpoint(endpoint)
                .keyExpr(key)
                .build()) {

            pub.start();
            System.out.println("[pure-java-simple-publisher] session OPEN");

            for (int i = 1; i <= count; i++) {
                String payload = "hello #" + i + " from pure-Java";
                pub.publish(payload.getBytes(StandardCharsets.UTF_8));
                System.out.println("[pure-java-simple-publisher] "
                        + i + "/" + count + " -> '" + payload + "'"
                        + " (sent=" + pub.getSentCount() + ")");
                if (i < count) Thread.sleep(interval);
            }

            System.out.println("[pure-java-simple-publisher] done, closing");
        }
    }
}
