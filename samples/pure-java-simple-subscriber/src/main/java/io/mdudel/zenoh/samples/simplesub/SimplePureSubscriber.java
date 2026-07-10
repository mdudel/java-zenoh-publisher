/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.samples.simplesub;

import io.mdudel.zenoh.purejava.PureJavaZenohSubscriber;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Absolute minimum viable pure-Java Zenoh subscriber.
 *
 * <p>Opens a session to a router, subscribes to a key expression
 * (defaults to the wildcard {@code demo/**}), prints one line per
 * received message. Runs until Ctrl-C, or optionally exits after a
 * configurable timeout. No JNI, no native binaries, no third-party
 * runtime dependencies.</p>
 *
 * <p>Positional args (all optional):</p>
 * <pre>
 * java -jar target/pure-java-simple-subscriber-0.1.0.jar
 *   [endpoint]         default: tcp/localhost:7447
 *   [keyExpr]          default: demo/**
 *   [timeoutSeconds]   default: 0 (run until Ctrl-C)
 * </pre>
 *
 * <p>Use with the {@code pure-java-simple-publisher} sample against
 * the same router to see messages flow end-to-end:</p>
 * <pre>
 * # Terminal 1 (subscriber):
 * java -jar samples/pure-java-simple-subscriber/target/pure-java-simple-subscriber-0.1.0.jar
 *
 * # Terminal 2 (publisher):
 * java -jar samples/pure-java-simple-publisher/target/pure-java-simple-publisher-0.1.0.jar
 * </pre>
 */
public final class SimplePureSubscriber {

    public static void main(String[] args) throws Exception {
        String endpoint       = args.length > 0 ? args[0] : "tcp/localhost:7447";
        String keyExpr        = args.length > 1 ? args[1] : "demo/**";
        long   timeoutSeconds = args.length > 2 ? Long.parseLong(args[2]) : 0L;

        System.out.println("[pure-java-simple-subscriber] endpoint=" + endpoint
                + " key=" + keyExpr
                + (timeoutSeconds > 0 ? " timeout=" + timeoutSeconds + "s" : " (Ctrl-C to stop)"));

        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(stop::countDown));

        try (PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint(endpoint)
                .build()) {

            sub.start();
            System.out.println("[pure-java-simple-subscriber] session OPEN");

            sub.subscribeAndConsume(keyExpr, sample ->
                    System.out.println("[pure-java-simple-subscriber] "
                            + sample.key() + " -> " + sample.payloadAsString()));

            if (timeoutSeconds > 0) {
                stop.await(timeoutSeconds, TimeUnit.SECONDS);
            } else {
                stop.await();
            }

            System.out.println("[pure-java-simple-subscriber] shutting down"
                    + " (received=" + sub.getReceivedCount() + ")");
        }
    }
}
