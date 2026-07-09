package io.mdudel.zenoh;

import io.zenoh.Zenoh;
import io.zenoh.Config;
import io.zenoh.Session;
import io.zenoh.bytes.ZBytes;
import io.zenoh.keyexpr.KeyExpr;
import io.zenoh.pubsub.Publisher;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Standalone Zenoh native-client publisher wrapper.
 *
 * <p>Extracted from the almondmalt {@code ZenohNativeEmitter} and stripped
 * of application-specific hooks (org.json, Emitter contract, ASTERIX/CoT
 * format code). This is the minimal, dependency-free surface needed to:</p>
 *
 * <ul>
 *   <li>Open a native Zenoh session against a router endpoint.</li>
 *   <li>Configure TLS / mTLS (root CA, client cert + key, hostname verify).</li>
 *   <li>Declare a base {@link Publisher} on a key expression.</li>
 *   <li>Optionally cache per-subkey publishers for {@code base/<sub>} routing.</li>
 *   <li>Publish {@code byte[]} payloads and track basic metrics.</li>
 * </ul>
 *
 * <h2>Endpoint syntax</h2>
 * <ul>
 *   <li>{@code tcp/host:port}, {@code udp/host:port}, {@code ws/host:port}
 *       &mdash; plaintext transports.</li>
 *   <li>{@code tls/host:port}, {@code quic/host:port}, {@code wss/host:port}
 *       &mdash; TLS transports. If a client cert + key are supplied, mTLS is
 *       enabled automatically.</li>
 * </ul>
 *
 * <h2>Optional org prefix</h2>
 *
 * <p>Fabrics that carve out per-tenant key prefixes (e.g. an ACL-restricted
 * router where each operator publishes under their own org id) can pass a
 * separate {@code org} string. It is prepended to the base key expression
 * at publish time as {@code org/keyExpr}, with slash normalisation, so
 * re-orging is a one-field edit and unit tests can pin the resolver.</p>
 *
 * <h2>Platform</h2>
 *
 * <p>{@code zenoh-java 1.9.0} bundles native libraries for
 * x86_64-linux-gnu, x86_64-apple-darwin, and x86_64-pc-windows-msvc.
 * On other architectures {@link Zenoh#open(Config)} will throw
 * {@link UnsatisfiedLinkError}, which is caught as {@link Throwable}
 * and re-thrown as {@link IOException} with a friendly message.</p>
 *
 * <h2>Thread-safety</h2>
 *
 * <p>{@link #start()} and {@link #stop()} are not reentrant &mdash; call
 * them once each per instance. {@link #publish(byte[])} is safe from
 * multiple threads once started; per-subkey publisher creation is
 * synchronised.</p>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * ZenohClient c = ZenohClient.builder()
 *         .connectEndpoint("tcp/localhost:7447")
 *         .keyExpr("demo/example/zenoh-java")
 *         .build();
 * c.start();
 * c.publish("hello".getBytes(StandardCharsets.UTF_8));
 * c.stop();
 * }</pre>
 */
public final class ZenohClient implements AutoCloseable {

    // ── config (immutable) ────────────────────────────────────────
    private final String connectEndpoint;
    private final String keyExpr;
    private final String org;
    private final String rootCaCertPath;
    private final String clientCertPath;
    private final String clientKeyPath;
    private final boolean verifyHostname;

    // ── runtime state ─────────────────────────────────────────────
    private volatile Session session;
    private volatile Publisher basePublisher;
    private final Map<String, Publisher> subPublishers = new HashMap<>();
    private volatile boolean active = false;
    private volatile String lastError;

    private final AtomicLong sentCount = new AtomicLong(0);
    private final AtomicLong lastSendMs = new AtomicLong(0);

    private ZenohClient(Builder b) {
        this.connectEndpoint = nz(b.connectEndpoint);
        this.keyExpr = b.keyExpr != null && !b.keyExpr.isEmpty() ? b.keyExpr : "demo/example/zenoh-java";
        this.org = nz(b.org);
        this.rootCaCertPath = nz(b.rootCaCertPath);
        this.clientCertPath = nz(b.clientCertPath);
        this.clientKeyPath = nz(b.clientKeyPath);
        this.verifyHostname = b.verifyHostname;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    // ── accessors ─────────────────────────────────────────────────
    public String getConnectEndpoint()   { return connectEndpoint; }
    public String getKeyExpr()           { return keyExpr; }
    public String getOrg()               { return org; }
    public String getEffectiveKeyExpr()  { return resolveKey(org, keyExpr); }
    public boolean isActive()            { return active; }
    public long getSentCount()           { return sentCount.get(); }
    public long getLastSendMs()          { return lastSendMs.get(); }
    public String getLastError()         { return lastError; }

    // ── lifecycle ─────────────────────────────────────────────────

    /** Open the Zenoh session and declare the base publisher. */
    public void start() throws IOException {
        if (active) return;

        System.out.println("[ZenohClient] starting connectEndpoint=\"" + connectEndpoint + "\""
                + " org=\"" + org + "\""
                + " key=\"" + keyExpr + "\""
                + " effectiveKey=\"" + getEffectiveKeyExpr() + "\""
                + " rootCa=" + (rootCaCertPath.isEmpty() ? "<EMPTY>" : rootCaCertPath)
                + " clientCert=" + (clientCertPath.isEmpty() ? "<EMPTY>" : clientCertPath)
                + " clientKey=" + (clientKeyPath.isEmpty() ? "<EMPTY>" : clientKeyPath)
                + " verifyHostname=" + verifyHostname);

        if (isPlainProto(connectEndpoint)
                && (!rootCaCertPath.isEmpty() || !clientCertPath.isEmpty())) {
            System.err.println("[ZenohClient] WARNING: cert paths configured but endpoint protocol is \""
                    + connectEndpoint.substring(0, connectEndpoint.indexOf('/'))
                    + "\" (plaintext). Certs will be IGNORED. Change to tls/quic/wss to use them.");
        }

        try {
            Config config = Config.loadDefault();
            if (!connectEndpoint.isEmpty()) {
                // Explicit client mode when connecting to a router; peer discovery
                // would otherwise hang for ~5 s trying to find peers via multicast.
                config.insertJson5("mode", "\"client\"");
                String escaped = connectEndpoint.replace("\"", "\\\"");
                config.insertJson5("connect/endpoints", "[\"" + escaped + "\"]");
            }

            if (isTlsProto(connectEndpoint)) {
                String tlsJson = buildTlsJson();
                config.insertJson5("transport/link/tls", tlsJson);
                System.out.println("[ZenohClient] TLS block=" + tlsJson);
                boolean mtls = !clientCertPath.isEmpty() && !clientKeyPath.isEmpty();
                if (!mtls) {
                    System.err.println("[ZenohClient] WARNING: endpoint is " + connectEndpoint
                            + " but mTLS NOT enabled (no client cert+key). Most TLS routers"
                            + " require mTLS and will close the handshake.");
                }
            }

            session = Zenoh.open(config);

            String pubKey = getEffectiveKeyExpr();
            basePublisher = session.declarePublisher(KeyExpr.tryFrom(pubKey));

            active = true;
            lastError = null;
            System.out.println("[ZenohClient] started \u2192 " + connectEndpoint
                    + " key=" + pubKey
                    + (!org.isEmpty() ? " (org=" + org + " + keyExpr=" + keyExpr + ")" : ""));
        } catch (Throwable e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            System.err.println("[ZenohClient] failed to start: " + lastError);
            safeClose();
            active = false;
            throw new IOException("Failed to start Zenoh client: " + lastError, e);
        }
    }

    /** Stop the session and release all publishers. Idempotent. */
    public void stop() {
        if (!active) return;
        safeClose();
        active = false;
        System.out.println("[ZenohClient] stopped");
    }

    @Override public void close() { stop(); }

    // ── publish API ───────────────────────────────────────────────

    /** Publish to the base key expression. */
    public void publish(byte[] data) throws IOException {
        publish(null, data);
    }

    /**
     * Publish to {@code effectiveKeyExpr/subKey} if {@code subKey} is non-null
     * and non-empty; otherwise to the base key. Per-sub publishers are declared
     * lazily and cached, so long-running processes should reuse the same
     * subKeys rather than randomising them (avoids unbounded map growth).
     */
    public void publish(String subKey, byte[] data) throws IOException {
        if (!active || basePublisher == null) {
            throw new IOException("ZenohClient is not started");
        }
        if (data == null || data.length == 0) return;
        try {
            ZBytes zbytes = ZBytes.from(data);
            if (subKey != null && !subKey.isEmpty()) {
                Publisher p = getOrCreateSubPublisher(subKey);
                if (p != null) p.put(zbytes);
                else basePublisher.put(zbytes);
            } else {
                basePublisher.put(zbytes);
            }
            sentCount.incrementAndGet();
            lastSendMs.set(System.currentTimeMillis());
        } catch (Throwable e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            throw new IOException("Zenoh put failed: " + lastError, e);
        }
    }

    private synchronized Publisher getOrCreateSubPublisher(String subKey) {
        Publisher p = subPublishers.get(subKey);
        if (p != null) return p;
        try {
            String full = getEffectiveKeyExpr() + "/" + subKey;
            p = session.declarePublisher(KeyExpr.tryFrom(full));
            subPublishers.put(subKey, p);
            return p;
        } catch (Throwable e) {
            System.err.println("[ZenohClient] declarePublisher(" + subKey + ") failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    private void safeClose() {
        try {
            synchronized (this) {
                for (Publisher p : subPublishers.values()) {
                    try { p.close(); } catch (Throwable ignored) {}
                }
                subPublishers.clear();
            }
            if (basePublisher != null) {
                try { basePublisher.close(); } catch (Throwable ignored) {}
                basePublisher = null;
            }
            if (session != null) {
                try { session.close(); } catch (Throwable ignored) {}
                session = null;
            }
        } catch (Throwable ignored) {}
    }

    // ── helpers ───────────────────────────────────────────────────

    private static boolean isPlainProto(String ep) {
        return ep.startsWith("tcp/") || ep.startsWith("udp/") || ep.startsWith("ws/");
    }

    private static boolean isTlsProto(String ep) {
        return ep.startsWith("tls/") || ep.startsWith("quic/") || ep.startsWith("wss/");
    }

    /**
     * Prepend {@code org} to {@code keyExpr} with slash normalisation.
     * Package-private for tests: {@code "foo/" + "/bar" -> "foo/bar"}.
     */
    static String resolveKey(String org, String keyExpr) {
        String k = keyExpr == null ? "" : keyExpr;
        if (org == null || org.isEmpty()) return k;
        String o = org;
        while (o.endsWith("/")) o = o.substring(0, o.length() - 1);
        while (k.startsWith("/")) k = k.substring(1);
        if (o.isEmpty()) return k;
        if (k.isEmpty()) return o;
        return o + "/" + k;
    }

    /**
     * Hand-build the TLS JSON block. We avoid pulling in a JSON dependency
     * because {@code zenoh-java}'s {@link Config#insertJson5(String, String)}
     * just needs a valid JSON5 string; escaping is only required for the
     * embedded path strings.
     */
    private String buildTlsJson() {
        boolean mtls = !clientCertPath.isEmpty() && !clientKeyPath.isEmpty();
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        if (!rootCaCertPath.isEmpty()) {
            sb.append("\"root_ca_certificate\":\"").append(escape(rootCaCertPath)).append("\"");
            first = false;
        }
        if (!clientCertPath.isEmpty()) {
            if (!first) sb.append(",");
            sb.append("\"connect_certificate\":\"").append(escape(clientCertPath)).append("\"");
            first = false;
        }
        if (!clientKeyPath.isEmpty()) {
            if (!first) sb.append(",");
            sb.append("\"connect_private_key\":\"").append(escape(clientKeyPath)).append("\"");
            first = false;
        }
        if (!first) sb.append(",");
        sb.append("\"enable_mtls\":").append(mtls);
        sb.append(",\"verify_name_on_connect\":").append(verifyHostname);
        sb.append("}");
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ── builder ───────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for {@link ZenohClient}. */
    public static final class Builder {
        private String connectEndpoint = "";
        private String keyExpr;
        private String org = "";
        private String rootCaCertPath = "";
        private String clientCertPath = "";
        private String clientKeyPath = "";
        private boolean verifyHostname = false;

        /** Endpoint, e.g. {@code tcp/localhost:7447} or {@code tls/router:7447}. */
        public Builder connectEndpoint(String v) { this.connectEndpoint = v; return this; }
        /** Base key expression, e.g. {@code demo/example/zenoh-java}. */
        public Builder keyExpr(String v)         { this.keyExpr = v; return this; }
        /** Optional per-tenant prefix; prepended to keyExpr at publish time. */
        public Builder org(String v)             { this.org = v; return this; }
        public Builder rootCaCertPath(String v)  { this.rootCaCertPath = v; return this; }
        public Builder clientCertPath(String v)  { this.clientCertPath = v; return this; }
        public Builder clientKeyPath(String v)   { this.clientKeyPath = v; return this; }
        /** Whether to verify the router hostname against the server cert SAN/CN. */
        public Builder verifyHostname(boolean v) { this.verifyHostname = v; return this; }

        public ZenohClient build() { return new ZenohClient(this); }
    }
}
