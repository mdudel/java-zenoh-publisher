/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.samples.puremtls;

import io.mdudel.zenoh.purejava.PureJavaZenohPublisher;

import java.nio.charset.StandardCharsets;

/**
 * Publisher over TLS with mutual authentication (mTLS), using the
 * pure-Java Zenoh 1.x publisher. Drop-in equivalent to the JNI
 * {@code mtls-publisher} sample in the sibling folder &mdash; same
 * positional argument shape, same PEM cert format, same visible
 * behaviour on the wire, but zero JNI and zero third-party runtime
 * deps beyond the JDK.
 *
 * <p>Enables mTLS by passing a client cert and a client private key
 * to the builder. The pure-Java facade auto-detects PEM vs PKCS12
 * from the file extension.</p>
 *
 * <p>Run:</p>
 * <pre>
 * cd samples/pure-java-mtls-publisher
 * mvn package
 * java -jar target/pure-java-mtls-publisher-0.1.0.jar \
 *       tls/router.example.com:7447 my/key \
 *       /etc/pki/ca.pem /etc/pki/client.pem /etc/pki/client.key
 * </pre>
 *
 * <p>Positional args (matches the JNI sibling):</p>
 * <ol>
 *   <li>{@code endpoint}       &mdash; must be {@code tls/...} or {@code wss/...}</li>
 *   <li>{@code keyExpr}        &mdash; key to publish under</li>
 *   <li>{@code rootCa}         &mdash; trust anchor PEM (or {@code .p12})</li>
 *   <li>{@code clientCert}     &mdash; your client certificate PEM (or {@code .p12})</li>
 *   <li>{@code clientKey}      &mdash; your client private key PEM (or same {@code .p12})</li>
 *   <li>{@code verifyHostname} &mdash; optional, {@code true}/{@code false} (default {@code true})</li>
 * </ol>
 */
public final class PureMtlsPublisher {

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("usage: PureMtlsPublisher <endpoint> <keyExpr> "
                    + "<rootCa> <clientCert> <clientKey> [verifyHostname]");
            System.err.println("example: PureMtlsPublisher tls/localhost:7447 demo/mtls/pure "
                    + "D:/ZENOH/certs/ca.pem D:/ZENOH/certs/client-cert.pem "
                    + "D:/ZENOH/certs/client-key.pem true");
            System.exit(2);
        }
        String  endpoint       = args[0];
        String  key            = args[1];
        String  rootCa         = args[2];
        String  clientCert     = args[3];
        String  clientKey      = args[4];
        boolean verifyHostname = args.length > 5 ? Boolean.parseBoolean(args[5]) : true;

        System.out.println("[PureMtlsPublisher] endpoint=" + endpoint + " key=" + key
                + " rootCa=" + rootCa + " clientCert=" + clientCert
                + " clientKey=" + clientKey + " verifyHostname=" + verifyHostname);

        try (PureJavaZenohPublisher pub = PureJavaZenohPublisher.builder()
                .connectEndpoint(endpoint)         // must be tls/ or wss/
                .keyExpr(key)
                .rootCaCertPath(rootCa)            // trust the router
                .clientCertPath(clientCert)        // present a client cert  ->
                .clientKeyPath(clientKey)          //   ... turns mTLS on
                .verifyHostname(verifyHostname)    // strict SAN/CN check
                .build()) {

            pub.start();
            System.out.println("[PureMtlsPublisher] session OPEN");

            byte[] payload = "secure hello from pure-Java".getBytes(StandardCharsets.UTF_8);
            pub.publish(payload);

            System.out.println("[PureMtlsPublisher] published " + payload.length
                    + "B to key=" + key + " via " + endpoint
                    + " (sent=" + pub.getSentCount() + ")");
        }
    }
}
