/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.samples.puremtlssub;

import io.mdudel.zenoh.purejava.PureJavaZenohSubscriber;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Subscriber over TLS with mutual authentication (mTLS), using the
 * pure-Java Zenoh 1.x publisher/subscriber module. Same positional-arg
 * contract as the {@code pure-java-mtls-publisher} sample.
 *
 * <p>Zero JNI. Zero third-party runtime dependencies beyond JDK 17.</p>
 *
 * <p>Run:</p>
 * <pre>
 * cd samples/pure-java-mtls-subscriber
 * mvn package
 * java -jar target/pure-java-mtls-subscriber-0.1.0.jar `
 *       tls/localhost:7447 `
 *       demo/mtls/pure `
 *       D:\ZENOH\certs\ca.pem `
 *       D:\ZENOH\certs\client-cert.pem `
 *       D:\ZENOH\certs\client-key.pem `
 *       true
 * </pre>
 *
 * <p>Positional args (matches the JNI sibling exactly):</p>
 * <ol>
 *   <li>{@code endpoint}         &mdash; must be {@code tls/...} or {@code wss/...}</li>
 *   <li>{@code keyExpr}          &mdash; key expression to subscribe to (wildcards welcome)</li>
 *   <li>{@code rootCa}           &mdash; trust anchor PEM (or {@code .p12})</li>
 *   <li>{@code clientCert}       &mdash; your client cert PEM (or {@code .p12})</li>
 *   <li>{@code clientKey}        &mdash; your client key PEM (or same {@code .p12})</li>
 *   <li>{@code verifyHostname}   &mdash; optional, {@code true}/{@code false} (default {@code true})</li>
 *   <li>{@code timeoutSeconds}   &mdash; optional, {@code 0} = forever (default {@code 0})</li>
 * </ol>
 */
public final class PureMtlsSubscriber {

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("usage: PureMtlsSubscriber <endpoint> <keyExpr> "
                    + "<rootCa> <clientCert> <clientKey> [verifyHostname] [timeoutSeconds]");
            System.err.println("example: PureMtlsSubscriber tls/localhost:7447 demo/mtls/pure "
                    + "D:/ZENOH/certs/ca.pem D:/ZENOH/certs/client-cert.pem "
                    + "D:/ZENOH/certs/client-key.pem true 30");
            System.exit(2);
        }
        String  endpoint       = args[0];
        String  key            = args[1];
        String  rootCa         = args[2];
        String  clientCert     = args[3];
        String  clientKey      = args[4];
        boolean verifyHostname = args.length > 5 ? Boolean.parseBoolean(args[5]) : true;
        long    timeoutSeconds = args.length > 6 ? Long.parseLong(args[6])       : 0L;

        System.out.println("[PureMtlsSubscriber] endpoint=" + endpoint + " key=" + key
                + " rootCa=" + rootCa + " clientCert=" + clientCert
                + " clientKey=" + clientKey + " verifyHostname=" + verifyHostname
                + (timeoutSeconds > 0 ? " timeout=" + timeoutSeconds + "s" : " (Ctrl-C to stop)"));

        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(stop::countDown));

        try (PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint(endpoint)         // must be tls/ or wss/
                .rootCaCertPath(rootCa)            // trust the router
                .clientCertPath(clientCert)        // present a client cert  ->
                .clientKeyPath(clientKey)          //   ... turns mTLS on
                .verifyHostname(verifyHostname)    // strict SAN/CN check
                .build()) {

            sub.start();
            System.out.println("[PureMtlsSubscriber] session OPEN");

            sub.subscribeAndConsume(key, sample ->
                    System.out.println("[PureMtlsSubscriber] "
                            + sample.key() + " -> " + sample.payloadAsString()));

            if (timeoutSeconds > 0) {
                stop.await(timeoutSeconds, TimeUnit.SECONDS);
            } else {
                stop.await();
            }

            System.out.println("[PureMtlsSubscriber] shutting down"
                    + " (received=" + sub.getReceivedCount() + ")");
        }
    }
}
