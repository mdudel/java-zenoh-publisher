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

import io.mdudel.zenoh.purejava.session.Sample;
import io.mdudel.zenoh.purejava.session.SessionException;
import io.mdudel.zenoh.purejava.session.SessionState;
import io.mdudel.zenoh.purejava.session.Subscription;
import io.mdudel.zenoh.purejava.session.ZenohSession;
import io.mdudel.zenoh.purejava.transport.TcpTransport;
import io.mdudel.zenoh.purejava.transport.TlsConfig;
import io.mdudel.zenoh.purejava.transport.TlsTransport;
import io.mdudel.zenoh.purejava.transport.Transport;
import io.mdudel.zenoh.purejava.transport.WsTransport;
import io.mdudel.zenoh.purejava.wire.KeyExpr;
import io.mdudel.zenoh.purejava.wire.messages.Declare;
import io.mdudel.zenoh.purejava.wire.messages.DeclareSubscriber;
import io.mdudel.zenoh.purejava.wire.messages.Interest;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Pure-Java Zenoh 1.x subscriber client. Sibling of
 * {@link PureJavaZenohPublisher}: same builder shape, same endpoint
 * parser, same TLS options. No JNI. No native binaries. Zero runtime
 * dependencies beyond JDK 17.
 *
 * <h2>Two ways to consume samples</h2>
 * <ul>
 *   <li><b>Pull</b> &mdash; call {@link #subscribe(String)}, then
 *       {@link Subscription#take()} / {@link Subscription#poll(long, java.util.concurrent.TimeUnit)}
 *       on the returned handle. Caller controls threading, backpressure
 *       is safe.</li>
 *   <li><b>Push (callback)</b> &mdash; call {@link #subscribeAndConsume(String, Consumer)}
 *       or {@link Subscription#forEach(Consumer)} on the returned handle.
 *       Callback runs on a fresh daemon thread; caller returns immediately.</li>
 * </ul>
 *
 * <p>Under the hood both use the same {@link Subscription} primitive; the
 * callback form is a thin wrapper around the pull queue.</p>
 *
 * <h2>Endpoint syntax</h2>
 * <p>Identical to {@link PureJavaZenohPublisher}. Accepts both Zenoh
 * classic {@code proto/host:port} form and URI form. Auto-detects PEM
 * vs PKCS12 by file extension for TLS.</p>
 *
 * <h2>Threading</h2>
 * <ul>
 *   <li>{@link #start()} single-threaded (call once).</li>
 *   <li>{@link #subscribe(String)} safe from any thread &mdash; delegates
 *       to {@link ZenohSession#declareSubscriber(KeyExpr)}.</li>
 *   <li>{@link #close()} idempotent from any thread; closes all live
 *       subscriptions in the order they were created.</li>
 * </ul>
 */
public final class PureJavaZenohSubscriber implements AutoCloseable {

    private static final Logger LOG = System.getLogger(PureJavaZenohSubscriber.class.getName());

    // ----- config (immutable) --------------------------------------------
    private final String  connectEndpoint;
    private final String  org;
    private final String  rootCaCertPath;
    private final String  clientCertPath;
    private final String  clientKeyPath;
    private final char[]  keyStorePassword;
    private final boolean verifyHostname;
    private final long    leaseMs;

    // ----- runtime state -------------------------------------------------
    private volatile ZenohSession session;
    private volatile Transport    transport;
    private volatile String       lastError;
    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    private PureJavaZenohSubscriber(Builder b) {
        this.connectEndpoint  = nz(b.connectEndpoint);
        this.org              = nz(b.org);
        this.rootCaCertPath   = nz(b.rootCaCertPath);
        this.clientCertPath   = nz(b.clientCertPath);
        this.clientKeyPath    = nz(b.clientKeyPath);
        this.keyStorePassword = b.keyStorePassword;
        this.verifyHostname   = b.verifyHostname;
        this.leaseMs          = b.leaseMs;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    // ----- accessors -----------------------------------------------------
    public String  getConnectEndpoint() { return connectEndpoint; }
    public String  getOrg()             { return org; }
    public boolean isActive() {
        return session != null && session.state() == SessionState.OPEN;
    }
    /** Cumulative count of samples delivered across ALL live subscriptions on this instance. */
    public long    getReceivedCount() {
        long total = 0;
        for (Subscription sub : subscriptions) total += sub.receivedCount();
        return total;
    }
    public String  getLastError()       { return lastError; }

    // ----- lifecycle -----------------------------------------------------

    /** Build the transport, run the INIT/OPEN handshake, be ready to subscribe. */
    public void start() throws IOException {
        if (isActive()) return;
        LOG.log(Level.INFO,
                "PureJavaZenohSubscriber.start() endpoint={0} org={1} verifyHostname={2} lease={3}ms",
                connectEndpoint, org, verifyHostname, leaseMs);
        Transport t;
        try {
            t = buildTransport();
        } catch (RuntimeException | IOException e) {
            lastError = "transport build failed: " + e.getMessage();
            throw wrapAsIo(e, lastError);
        }
        ZenohSession s = ZenohSession.builder(t)
                .leaseMs(leaseMs)
                .autoConnect(true)
                .build();
        try {
            s.open();
        } catch (SessionException e) {
            lastError = "session open failed: " + e.getMessage();
            s.close();
            t.close();
            throw new IOException(lastError, e);
        }
        this.transport = t;
        this.session   = s;
    }

    /** Close all subscriptions, then the session, then the transport. Idempotent. */
    public void stop() {
        for (Subscription sub : subscriptions) {
            try { sub.close(); } catch (RuntimeException ignored) {}
        }
        subscriptions.clear();
        ZenohSession s = session;
        if (s != null) {
            s.close();
            session = null;
        }
        transport = null;
        LOG.log(Level.INFO, "PureJavaZenohSubscriber.stop()");
    }

    @Override public void close() { stop(); }

    // ----- subscribe -----------------------------------------------------

    /**
     * Declare a subscription and return the handle. Key expression may
     * contain wildcards; see {@link KeyExpr}. If {@code org} is set on
     * the builder, it is prepended to {@code keyExpr} using
     * {@link KeyExpr#resolveKey(String, String)} for symmetry with the
     * publisher facade.
     */
    public Subscription subscribe(String keyExpr) throws IOException {
        Objects.requireNonNull(keyExpr, "keyExpr");
        ZenohSession s = session;
        if (s == null || s.state() != SessionState.OPEN) {
            throw new IOException("PureJavaZenohSubscriber is not started");
        }
        String effective = KeyExpr.resolveKey(org, keyExpr);
        try {
            Subscription sub = s.declareSubscriber(KeyExpr.of(effective));
            subscriptions.add(sub);
            return sub;
        } catch (SessionException e) {
            lastError = "subscribe failed: " + e.getMessage();
            throw new IOException(lastError, e);
        }
    }

    /**
     * Sugar: subscribe and attach a callback in one call. The callback
     * runs on a fresh daemon thread and does not block the caller;
     * caller keeps the returned {@link Subscription} to close it later.
     */
    public Subscription subscribeAndConsume(String keyExpr, Consumer<Sample> onSample) throws IOException {
        Subscription sub = subscribe(keyExpr);
        sub.forEach(onSample);
        return sub;
    }

    // ----- topic discovery ---------------------------------------------

    /**
     * A running topic-discovery handle. Returned by
     * {@link #discoverTopics(String, TopicListener)}. Call {@link #close()}
     * to send a FINAL INTEREST and stop receiving updates.
     */
    public final class TopicDiscovery implements AutoCloseable {
        private final long interestId;
        private final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        TopicDiscovery(long id) { this.interestId = id; }
        public long interestId() { return interestId; }
        public boolean isOpen() { return !closed.get(); }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            ZenohSession s = session;
            if (s == null) return;
            try { s.finalInterest(interestId); }
            catch (SessionException e) {
                LOG.log(Level.DEBUG, () -> "finalInterest failed: " + e.getMessage());
            }
        }
    }

    /**
     * Callback surface for topic discovery. Fires once per DECLARE record
     * the router returns matching an INTEREST.
     *
     * <ul>
     *   <li>{@link #onTopicDeclared(String, long)} — a new subscription
     *       exists in the network for the given key expression.</li>
     *   <li>{@link #onTopicUndeclared(long)} — a previously-seen
     *       subscription is gone.</li>
     *   <li>{@link #onDiscoveryComplete()} — the router finished
     *       replaying current state; further callbacks (if any) are
     *       live updates. Only fires for CURRENT / CURRENT_FUTURE modes.</li>
     * </ul>
     *
     * <p>Default {@code onTopicUndeclared} and {@code onDiscoveryComplete}
     * are no-ops so simple use cases can just implement
     * {@code onTopicDeclared}.</p>
     */
    public interface TopicListener {
        void onTopicDeclared(String keyExpr, long declaredById);
        default void onTopicUndeclared(long declaredById) {}
        default void onDiscoveryComplete() {}
    }

    /**
     * Discover subscriber topics matching a key expression pattern.
     * Sends a CURRENT_FUTURE INTEREST to the router; the listener fires
     * once per existing DeclareSubscriber matching the pattern, then
     * {@link TopicListener#onDiscoveryComplete()}, then for future
     * subscriptions as they come and go.
     *
     * <p>Note: this returns declarations SEEN BY THE ROUTER, not
     * necessarily the set of keys anyone has ever published under.
     * If there is no subscriber for a key, that key does not appear
     * here even if there is an active publisher for it. To catch
     * publishers directly, subscribe to {@code **} with
     * {@link #subscribeAndConsume} and observe {@link Sample#key()}.</p>
     *
     * @param keyExprPattern KeyExpr pattern to filter on (wildcards
     *                       welcome; use {@code "**"} to see everything).
     */
    public TopicDiscovery discoverTopics(String keyExprPattern, TopicListener listener) throws IOException {
        Objects.requireNonNull(keyExprPattern, "keyExprPattern");
        Objects.requireNonNull(listener,       "listener");
        ZenohSession s = session;
        if (s == null || s.state() != SessionState.OPEN) {
            throw new IOException("PureJavaZenohSubscriber is not started");
        }
        try {
            long id = s.declareInterest(Interest.Mode.CURRENT_FUTURE,
                    Interest.OPT_SUBSCRIBERS, keyExprPattern,
                    (declare, isFinal) -> {
                        if (isFinal) {
                            try { listener.onDiscoveryComplete(); }
                            catch (RuntimeException re) { /* absorb */ }
                            return;
                        }
                        Declare.Body body = declare.body();
                        if (body.kind() == Declare.Body.BodyKind.DECLARE_SUBSCRIBER) {
                            DeclareSubscriber ds = body.asDeclareSubscriber();
                            String k = ds.keySuffix() == null ? "" : ds.keySuffix();
                            try { listener.onTopicDeclared(k, ds.id()); }
                            catch (RuntimeException re) { /* absorb */ }
                        } else if (body.kind() == Declare.Body.BodyKind.UNDECLARE_SUBSCRIBER) {
                            try { listener.onTopicUndeclared(body.asUndeclareSubscriber().id()); }
                            catch (RuntimeException re) { /* absorb */ }
                        }
                    });
            return new TopicDiscovery(id);
        } catch (SessionException e) {
            lastError = "discoverTopics failed: " + e.getMessage();
            throw new IOException(lastError, e);
        }
    }

    /**
     * Sugar: {@code discoverTopics("**", listener)}. Watches for every
     * subscription the router knows about.
     */
    public TopicDiscovery discoverAllTopics(TopicListener listener) throws IOException {
        return discoverTopics("**", listener);
    }

    // ----- topic listing (synchronous one-shot) ------------------------

    /**
     * A single topic entry as reported by the router in response to a
     * one-shot {@link #listTopicsNow(String, long)} call. Backed by the
     * key expression the router saw declared, plus the peer-local
     * subscriber id the router associates with it.
     *
     * <p>{@code declaredById} is the id the declaring peer chose for its
     * own subscription. It is only meaningful in the scope of the router
     * that reported it, and is included here so callers can correlate
     * later undeclare events (from {@link #discoverTopics}) with entries
     * in an earlier snapshot. If you don't need the correlation, ignore
     * the field.</p>
     */
    public record Topic(String keyExpr, long declaredById) {

        /**
         * Serialise a list of topics to a compact JSON array of
         * {@code {"keyExpr":..., "declaredById":...}} objects. Uses only
         * JDK APIs, in keeping with the pure-Java module's zero-runtime-
         * dependency invariant. Suitable for piping into {@code jq}, an
         * HTTP response, a log line, or a file.
         */
        public static String toJson(java.util.List<Topic> topics) {
            java.util.Objects.requireNonNull(topics, "topics");
            StringBuilder sb = new StringBuilder(64 + topics.size() * 48);
            sb.append('[');
            boolean first = true;
            for (Topic t : topics) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"keyExpr\":\"");
                escapeJson(sb, t.keyExpr());
                sb.append("\",\"declaredById\":").append(t.declaredById()).append('}');
            }
            sb.append(']');
            return sb.toString();
        }

        private static void escapeJson(StringBuilder sb, String s) {
            if (s == null) return;
            for (int i = 0, n = s.length(); i < n; i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"'  -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else          sb.append(c);
                    }
                }
            }
        }
    }

    /**
     * Synchronously ask the router for its current set of subscriber
     * declarations matching a key-expression pattern, and return them as
     * an ordered snapshot list. Blocks the calling thread until either
     * the router replays its current state (a FINAL sentinel arrives) or
     * {@code timeoutMs} elapses; on timeout, returns whatever has been
     * collected so far rather than throwing.
     *
     * <p>Wire mechanism: sends an INTEREST in {@link Interest.Mode#CURRENT}
     * mode with the SUBSCRIBERS option set. The interest self-terminates
     * as soon as the router finishes replaying — no follow-up
     * {@code FINAL} needs to be sent by the caller, and no future-mode
     * updates will arrive after this method returns.</p>
     *
     * <h3>What this shows you (and what it does not)</h3>
     * <p>The list contains one entry per subscription the router
     * currently knows about that matches the pattern. If two peers have
     * subscribed to the same key expression, you'll see two entries with
     * the same {@code keyExpr} and different {@code declaredById}s. A
     * key expression that has publishers but no subscribers does
     * <b>not</b> appear here — the Zenoh 1.x wire protocol has no
     * corresponding {@code DeclarePublisher} for the router to report,
     * so publishers are effectively invisible to discovery. To catch
     * traffic from publishers directly, subscribe to {@code **} with
     * {@link #subscribeAndConsume} and inspect {@link Sample#key()}.</p>
     *
     * @param keyExprPattern KeyExpr pattern to filter on. Wildcards are
     *                       welcome; pass {@code "**"} for everything.
     * @param timeoutMs      maximum time to wait for the router's FINAL
     *                       sentinel, in milliseconds. Must be positive.
     * @return an immutable snapshot list, ordered by the sequence in
     *         which the router reported entries. Empty if the router has
     *         no matching subscribers, or if the timeout elapsed before
     *         anything arrived.
     * @throws IOException if the subscriber is not started, or the
     *         underlying interest could not be sent.
     */
    public java.util.List<Topic> listTopicsNow(String keyExprPattern, long timeoutMs) throws IOException {
        Objects.requireNonNull(keyExprPattern, "keyExprPattern");
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be > 0, got " + timeoutMs);
        }
        ZenohSession s = session;
        if (s == null || s.state() != SessionState.OPEN) {
            throw new IOException("PureJavaZenohSubscriber is not started");
        }

        java.util.List<Topic> collected = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        long interestId;
        try {
            interestId = s.declareInterest(Interest.Mode.CURRENT,
                    Interest.OPT_SUBSCRIBERS, keyExprPattern,
                    (declare, isFinal) -> {
                        if (isFinal) {
                            done.countDown();
                            return;
                        }
                        Declare.Body body = declare.body();
                        if (body.kind() == Declare.Body.BodyKind.DECLARE_SUBSCRIBER) {
                            var ds = body.asDeclareSubscriber();
                            String k = ds.keySuffix() == null ? "" : ds.keySuffix();
                            collected.add(new Topic(k, ds.id()));
                        }
                    });
        } catch (io.mdudel.zenoh.purejava.session.SessionException e) {
            lastError = "listTopicsNow failed: " + e.getMessage();
            throw new IOException(lastError, e);
        }

        boolean completed;
        try {
            completed = done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            LOG.log(Level.DEBUG, () -> "listTopicsNow interrupted after "
                    + collected.size() + " entries");
            // Best-effort cleanup: FINAL the interest so the router
            // stops sending. CURRENT-mode interests self-terminate on
            // the router side once state replay completes, but if we're
            // bailing out early it's polite to tell the router.
            try { s.finalInterest(interestId); } catch (Exception ignored) {}
            return java.util.List.copyOf(collected);
        }

        if (!completed) {
            LOG.log(Level.DEBUG, () -> "listTopicsNow timed out after "
                    + timeoutMs + "ms with " + collected.size()
                    + " entries collected (no FINAL sentinel from router)");
            try { s.finalInterest(interestId); } catch (Exception ignored) {}
        }
        return java.util.List.copyOf(collected);
    }

    /**
     * Sugar: {@code listTopicsNow("**", timeoutMs)}. Snapshot every
     * subscriber declaration the router currently knows about.
     */
    public java.util.List<Topic> listAllTopicsNow(long timeoutMs) throws IOException {
        return listTopicsNow("**", timeoutMs);
    }

    // ----- endpoint parsing + transport factory --------------------------
    // (Same logic as PureJavaZenohPublisher; kept in sync deliberately.
    //  A shared TransportFactory helper would be nicer -- see followup below.)

    Transport buildTransport() throws IOException {
        if (connectEndpoint.isEmpty()) {
            throw new IOException("connectEndpoint is required");
        }
        String scheme;
        String host;
        int    port;
        String path = "";

        if (connectEndpoint.contains("://")) {
            URI uri = URI.create(connectEndpoint);
            scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            host   = uri.getHost();
            port   = uri.getPort();
            path   = uri.getRawPath() == null ? "" : uri.getRawPath();
        } else {
            int slash = connectEndpoint.indexOf('/');
            if (slash <= 0) {
                throw new IOException("connectEndpoint must be proto/host:port or a URI: "
                        + connectEndpoint);
            }
            scheme = connectEndpoint.substring(0, slash).toLowerCase();
            String hostPort = connectEndpoint.substring(slash + 1);
            int colon = hostPort.indexOf(':');
            if (colon <= 0 || colon == hostPort.length() - 1) {
                throw new IOException("connectEndpoint must contain host:port: "
                        + connectEndpoint);
            }
            host = hostPort.substring(0, colon);
            try { port = Integer.parseInt(hostPort.substring(colon + 1)); }
            catch (NumberFormatException nfe) {
                throw new IOException("invalid port in endpoint: " + connectEndpoint);
            }
        }
        if (host == null || host.isEmpty()) {
            throw new IOException("host missing from endpoint: " + connectEndpoint);
        }
        if (port < 1 || port > 65535) {
            throw new IOException("port out of range in endpoint: " + connectEndpoint);
        }

        return switch (scheme) {
            case "tcp" -> new TcpTransport(host, port);
            case "tls" -> new TlsTransport(host, port, buildTlsConfig());
            case "ws"  -> new WsTransport(URI.create("ws://" + host + ":" + port + path), null);
            case "wss" -> new WsTransport(
                    URI.create("wss://" + host + ":" + port + path), buildTlsConfig());
            default -> throw new IOException("unsupported endpoint scheme '" + scheme
                    + "'; expected one of tcp, tls, ws, wss");
        };
    }

    private TlsConfig buildTlsConfig() throws IOException {
        TlsConfig.Builder tb = TlsConfig.builder().verifyHostname(verifyHostname);

        if (!rootCaCertPath.isEmpty()) {
            Path p = requireReadable(rootCaCertPath, "rootCaCertPath");
            if (isPem(p))         tb.trustStorePem(p);
            else if (isPkcs12(p)) tb.trustStore(p, keyStorePassword);
            else throw new IOException(
                    "rootCaCertPath must end in .pem/.crt/.cer (PEM) or .p12/.pfx (PKCS12): "
                            + rootCaCertPath);
        } else {
            tb.trustSystem();
        }

        if (!clientCertPath.isEmpty() && !clientKeyPath.isEmpty()) {
            Path cert = requireReadable(clientCertPath, "clientCertPath");
            Path key  = requireReadable(clientKeyPath,  "clientKeyPath");
            if (isPem(cert) && isPem(key)) {
                tb.keyStorePem(cert, key);
            } else if (isPkcs12(cert) && cert.equals(key)) {
                tb.keyStore(cert, keyStorePassword, keyStorePassword);
            } else if (isPkcs12(cert) || isPkcs12(key)) {
                throw new IOException(
                        "for PKCS12 client keystore, set clientCertPath and clientKeyPath "
                                + "to the SAME .p12 file (or leave one empty); PEM keys need "
                                + "BOTH clientCertPath (.pem) AND clientKeyPath (.key/.pem) set");
            } else {
                throw new IOException(
                        "clientCertPath / clientKeyPath must be PEM pair (.pem/.crt/.cer + .pem/.key) "
                                + "or PKCS12 (.p12/.pfx)");
            }
        } else if (!clientCertPath.isEmpty() || !clientKeyPath.isEmpty()) {
            String path = !clientCertPath.isEmpty() ? clientCertPath : clientKeyPath;
            Path p = requireReadable(path,
                    !clientCertPath.isEmpty() ? "clientCertPath" : "clientKeyPath");
            if (isPkcs12(p)) {
                tb.keyStore(p, keyStorePassword, keyStorePassword);
            } else {
                throw new IOException(
                        "PEM client authentication requires BOTH clientCertPath (.pem) AND "
                                + "clientKeyPath (.key or .pem) to be set; got only " + path);
            }
        }
        return tb.build();
    }

    private static Path requireReadable(String pathStr, String argName) throws IOException {
        Path p = Paths.get(pathStr);
        if (!Files.isReadable(p)) {
            throw new IOException(argName + " not readable: " + pathStr);
        }
        return p;
    }

    private static boolean isPem(Path p) {
        String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".pem") || n.endsWith(".crt") || n.endsWith(".cer") || n.endsWith(".key");
    }

    private static boolean isPkcs12(Path p) {
        String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".p12") || n.endsWith(".pfx");
    }

    private static IOException wrapAsIo(Throwable e, String message) {
        if (e instanceof IOException io) return io;
        return new IOException(message, e);
    }

    // ----- builder -------------------------------------------------------

    public static Builder builder() { return new Builder(); }

    /**
     * Fluent builder mirroring {@link PureJavaZenohPublisher.Builder}
     * so switching between the two facades at a call site is a
     * one-line change.
     */
    public static final class Builder {
        private String  connectEndpoint  = "";
        private String  org              = "";
        private String  rootCaCertPath   = "";
        private String  clientCertPath   = "";
        private String  clientKeyPath    = "";
        private char[]  keyStorePassword = "changeit".toCharArray();
        private boolean verifyHostname   = true;
        private long    leaseMs          = ZenohSession.DEFAULT_LEASE_MS;

        public Builder connectEndpoint(String v)  { this.connectEndpoint  = v; return this; }
        public Builder org(String v)              { this.org              = v; return this; }
        public Builder rootCaCertPath(String v)   { this.rootCaCertPath   = v; return this; }
        public Builder clientCertPath(String v)   { this.clientCertPath   = v; return this; }
        public Builder clientKeyPath(String v)    { this.clientKeyPath    = v; return this; }
        public Builder keyStorePassword(char[] v) { this.keyStorePassword = v; return this; }
        public Builder verifyHostname(boolean v)  { this.verifyHostname   = v; return this; }
        public Builder leaseMs(long v)            { this.leaseMs          = v; return this; }

        public PureJavaZenohSubscriber build() { return new PureJavaZenohSubscriber(this); }
    }

    // ----- CLI ----------------------------------------------------------

    /**
     * CLI: subscribe to a key expression and print each received message
     * as a line on stdout. Runs until interrupted (Ctrl-C) or until an
     * optional {@code timeoutSeconds} elapses.
     *
     * <p>Usage:</p>
     * <pre>
     * java -jar pure-java-simple-subscriber.jar
     * java -jar pure-java-simple-subscriber.jar tcp/router:7447 demo/**
     * java -jar pure-java-simple-subscriber.jar tcp/router:7447 demo/** 30
     * </pre>
     */
    public static void main(String[] args) throws Exception {
        String endpoint = args.length > 0 ? args[0] : "tcp/localhost:7447";
        String keyExpr  = args.length > 1 ? args[1] : "demo/**";
        long   timeoutSeconds = args.length > 2 ? Long.parseLong(args[2]) : 0;   // 0 = forever

        System.out.println("[pure-java-subscriber] endpoint=" + endpoint + " key=" + keyExpr
                + (timeoutSeconds > 0 ? " timeout=" + timeoutSeconds + "s" : " (Ctrl-C to stop)"));

        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(stop::countDown));

        try (PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint(endpoint)
                .build()) {
            sub.start();
            System.out.println("[pure-java-subscriber] session OPEN");
            sub.subscribeAndConsume(keyExpr, sample ->
                    System.out.println("[pure-java-subscriber] "
                            + sample.key() + " -> " + sample.payloadAsString()));
            if (timeoutSeconds > 0) {
                stop.await(timeoutSeconds, TimeUnit.SECONDS);
            } else {
                stop.await();
            }
            System.out.println("[pure-java-subscriber] shutting down"
                    + " (received=" + sub.getReceivedCount() + ")");
        }
    }

}
