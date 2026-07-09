/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of a clean-room pure-Java implementation of the Eclipse
 * Zenoh 1.x wire protocol. It is not a copy of any Zenoh source code.
 */
package io.mdudel.zenoh.purejava;

import io.mdudel.zenoh.purejava.wire.KeyExpr;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure-Java Zenoh 1.x publisher client. No JNI. No native binaries. Zero
 * runtime dependencies beyond JDK 17.
 *
 * <h2>Status</h2>
 * <p><b>MVP - not yet functional.</b> The API surface (builder, start/stop/
 * publish signatures, getters, config resolution) is stable and mirrors the
 * JNI-backed {@code io.mdudel.zenoh.ZenohClient} in the sibling module so the
 * two are drop-in swappable at a call site. The wire codec, session state
 * machine, and transports are being built in follow-up commits.</p>
 *
 * <p>Calling {@link #start()} or {@link #publish(byte[])} today throws
 * {@link UnsupportedOperationException}. The class exists so the design and
 * integration point can be reviewed before the wire code lands.</p>
 *
 * <h2>Design</h2>
 * <ul>
 *   <li><b>Blocking I/O per session, one reader thread</b> for router
 *       responses / KeepAlive. Publishes serialise on a per-session
 *       {@code synchronized} lock. Fine for the dozens-of-tracks-per-second
 *       scale the sibling {@code ZenohClient} handles; scale by creating
 *       multiple publisher instances.</li>
 *   <li><b>Logging via {@link System.Logger}</b> so this module has zero
 *       logging dependencies. Users bridge to SLF4J / Log4j / Logback by
 *       providing a {@code System.LoggerFinder} on their classpath.</li>
 *   <li><b>API mirror</b> of the JNI-backed {@code ZenohClient} builder,
 *       so choosing between the two implementations is a one-line change.
 *       Same TLS PEM handling, same org-prefix key resolution (see
 *       {@link KeyExpr#resolveKey(String, String)}), same warning logs.</li>
 * </ul>
 *
 * <h2>Supported transports (planned)</h2>
 * <ul>
 *   <li>{@code tcp/host:port} - plaintext TCP</li>
 *   <li>{@code tls/host:port} - TLS + optional mTLS via
 *       {@link javax.net.ssl.SSLSocket}</li>
 *   <li>{@code ws/host:port}  - plaintext WebSocket via
 *       {@link java.net.http.WebSocket}</li>
 *   <li>{@code wss/host:port} - TLS WebSocket via
 *       {@link java.net.http.WebSocket}</li>
 * </ul>
 *
 * <p>Deferred: UDP, QUIC. See {@code README.md} for rationale.</p>
 */
public final class PureJavaZenohPublisher implements AutoCloseable {

    private static final Logger LOG = System.getLogger(PureJavaZenohPublisher.class.getName());

    // ----- config (immutable) --------------------------------------------
    private final String connectEndpoint;
    private final String keyExpr;
    private final String org;
    private final String rootCaCertPath;
    private final String clientCertPath;
    private final String clientKeyPath;
    private final boolean verifyHostname;

    // ----- runtime state -------------------------------------------------
    private volatile boolean active = false;
    private volatile String lastError;
    private final AtomicLong sentCount  = new AtomicLong(0);
    private final AtomicLong lastSendMs = new AtomicLong(0);

    private PureJavaZenohPublisher(Builder b) {
        this.connectEndpoint = nz(b.connectEndpoint);
        this.keyExpr         = (b.keyExpr != null && !b.keyExpr.isEmpty())
                                 ? b.keyExpr : "demo/example/zenoh-java";
        this.org             = nz(b.org);
        this.rootCaCertPath  = nz(b.rootCaCertPath);
        this.clientCertPath  = nz(b.clientCertPath);
        this.clientKeyPath   = nz(b.clientKeyPath);
        this.verifyHostname  = b.verifyHostname;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    // ----- accessors -----------------------------------------------------
    public String  getConnectEndpoint()  { return connectEndpoint; }
    public String  getKeyExpr()          { return keyExpr; }
    public String  getOrg()              { return org; }
    public String  getEffectiveKeyExpr() { return KeyExpr.resolveKey(org, keyExpr); }
    public boolean isActive()            { return active; }
    public long    getSentCount()        { return sentCount.get(); }
    public long    getLastSendMs()       { return lastSendMs.get(); }
    public String  getLastError()        { return lastError; }

    // ----- lifecycle -----------------------------------------------------

    /**
     * Open the transport, do the Zenoh Init/Open handshake, declare the
     * base publisher.
     *
     * @throws UnsupportedOperationException while the MVP is being built.
     */
    public void start() throws IOException {
        if (active) return;
        LOG.log(Level.INFO,
                "PureJavaZenohPublisher.start() endpoint={0} key={1} effectiveKey={2}"
                        + " org={3} verifyHostname={4}",
                connectEndpoint, keyExpr, getEffectiveKeyExpr(), org, verifyHostname);
        throw new UnsupportedOperationException(
                "PureJavaZenohPublisher is an MVP; wire protocol not yet"
                        + " implemented. See README.md for scope and status.");
    }

    /** Stop the session, close the transport. Idempotent. */
    public void stop() {
        if (!active) return;
        active = false;
        LOG.log(Level.INFO, "PureJavaZenohPublisher.stop()");
    }

    @Override public void close() { stop(); }

    // ----- publish -------------------------------------------------------

    /** Publish to the base key expression (with any {@code org} prefix). */
    public void publish(byte[] data) throws IOException {
        publish(null, data);
    }

    /**
     * Publish to {@code effectiveKeyExpr/subKey} if {@code subKey} is
     * non-null and non-empty, otherwise to the base key. Matches the
     * per-subkey routing behaviour of the JNI publisher's per-track
     * publisher cache.
     *
     * @throws UnsupportedOperationException while the MVP is being built.
     */
    public synchronized void publish(String subKey, byte[] data) throws IOException {
        if (!active) {
            throw new IOException("PureJavaZenohPublisher is not started");
        }
        throw new UnsupportedOperationException(
                "PureJavaZenohPublisher.publish is not yet implemented"
                        + " (MVP scaffolding turn). See README.md.");
    }

    // ----- builder -------------------------------------------------------

    public static Builder builder() { return new Builder(); }

    /**
     * Fluent builder mirroring the shape of
     * {@code io.mdudel.zenoh.ZenohClient.Builder} so switching between the
     * JNI-backed and pure-Java publishers is a one-line change at the call
     * site.
     */
    public static final class Builder {
        private String  connectEndpoint = "";
        private String  keyExpr;
        private String  org             = "";
        private String  rootCaCertPath  = "";
        private String  clientCertPath  = "";
        private String  clientKeyPath   = "";
        private boolean verifyHostname  = false;

        public Builder connectEndpoint(String v) { this.connectEndpoint = v; return this; }
        public Builder keyExpr(String v)         { this.keyExpr         = v; return this; }
        public Builder org(String v)             { this.org             = v; return this; }
        public Builder rootCaCertPath(String v)  { this.rootCaCertPath  = v; return this; }
        public Builder clientCertPath(String v)  { this.clientCertPath  = v; return this; }
        public Builder clientKeyPath(String v)   { this.clientKeyPath   = v; return this; }
        public Builder verifyHostname(boolean v) { this.verifyHostname  = v; return this; }

        public PureJavaZenohPublisher build() { return new PureJavaZenohPublisher(this); }
    }

    // ----- CLI stub ------------------------------------------------------

    /**
     * Placeholder main so {@code java -jar} on the packaged jar prints a
     * useful status message rather than "no main manifest attribute".
     * Full CLI will follow the sibling module's {@code ZenohPublisherApp}
     * shape once the publisher is functional.
     */
    public static void main(String[] args) {
        System.out.println("java-zenoh-publisher-pure - pure-Java Zenoh 1.x publisher (MVP)");
        System.out.println();
        System.out.println("Status: scaffolding only, wire protocol not yet implemented.");
        System.out.println("See README.md for the roadmap and current scope.");
        System.out.println();
        System.out.println("Effective build:");
        System.out.println("  JDK        = " + System.getProperty("java.version"));
        System.out.println("  Class      = " + PureJavaZenohPublisher.class.getName());
        System.out.println();
        System.out.println("Try (throws UnsupportedOperationException today):");
        System.out.println("  PureJavaZenohPublisher.builder()");
        System.out.println("      .connectEndpoint(\"tcp/localhost:7447\")");
        System.out.println("      .keyExpr(\"demo/greeting\")");
        System.out.println("      .build()");
        System.out.println("      .start();");
    }
}
