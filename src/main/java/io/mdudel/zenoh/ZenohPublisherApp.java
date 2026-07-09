package io.mdudel.zenoh;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal starter application demonstrating {@link ZenohClient}.
 *
 * <p>Two modes:</p>
 * <ul>
 *   <li><b>--rate=N</b> (default 1) publish {@code --message} at N Hz until
 *       Ctrl-C. Payload has a monotonic counter appended so the receiver
 *       can eyeball loss.</li>
 *   <li><b>--stdin</b> read lines from stdin and publish each line as a
 *       separate payload. EOF stops.</li>
 * </ul>
 *
 * <p>Run with {@code -h} for the full flag list.</p>
 */
public final class ZenohPublisherApp {

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parse(args);
        if (opts.containsKey("h") || opts.containsKey("help")) {
            usage();
            return;
        }

        ZenohClient client = ZenohClient.builder()
                .connectEndpoint(opts.getOrDefault("endpoint", ""))
                .keyExpr(opts.getOrDefault("key", "demo/example/zenoh-java"))
                .org(opts.getOrDefault("org", ""))
                .rootCaCertPath(opts.getOrDefault("root-ca", ""))
                .clientCertPath(opts.getOrDefault("client-cert", ""))
                .clientKeyPath(opts.getOrDefault("client-key", ""))
                .verifyHostname(Boolean.parseBoolean(opts.getOrDefault("verify-hostname", "false")))
                .build();

        client.start();
        Runtime.getRuntime().addShutdownHook(new Thread(client::stop, "zenoh-shutdown"));

        if (opts.containsKey("stdin")) {
            runStdin(client);
            return;
        }

        String message = opts.getOrDefault("message", "hello from java-zenoh-publisher");
        double rate = parseDouble(opts.getOrDefault("rate", "1"), 1.0);
        long periodMs = rate <= 0 ? 1000L : Math.max(1L, (long) (1000.0 / rate));
        long total = parseLong(opts.getOrDefault("count", "0"), 0L); // 0 = forever
        String subKey = opts.getOrDefault("sub", "");

        System.out.println("[ZenohPublisherApp] publishing key=" + client.getEffectiveKeyExpr()
                + (subKey.isEmpty() ? "" : "/" + subKey)
                + " rate=" + rate + "Hz"
                + (total > 0 ? " count=" + total : " count=forever"));

        long sent = 0;
        long start = System.currentTimeMillis();
        while (total == 0 || sent < total) {
            String payload = message + " #" + sent
                    + " t=" + (System.currentTimeMillis() - start) + "ms";
            client.publish(subKey.isEmpty() ? null : subKey,
                    payload.getBytes(StandardCharsets.UTF_8));
            sent++;
            if (sent % 100 == 0) {
                System.out.println("[ZenohPublisherApp] sent=" + sent);
            }
            try {
                Thread.sleep(periodMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        client.stop();
    }

    private static void runStdin(ZenohClient client) throws Exception {
        System.out.println("[ZenohPublisherApp] reading from stdin (EOF to stop) key="
                + client.getEffectiveKeyExpr());
        try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;
                client.publish(line.getBytes(StandardCharsets.UTF_8));
            }
        }
        client.stop();
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (String a : args) {
            if (a.startsWith("--")) a = a.substring(2);
            else if (a.startsWith("-")) a = a.substring(1);
            int eq = a.indexOf('=');
            if (eq < 0) m.put(a.toLowerCase(Locale.ROOT), "true");
            else m.put(a.substring(0, eq).toLowerCase(Locale.ROOT), a.substring(eq + 1));
        }
        return m;
    }

    private static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s); } catch (Exception e) { return def; }
    }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s); } catch (Exception e) { return def; }
    }

    private static void usage() {
        System.out.println("java-zenoh-publisher — minimal native Zenoh client starter");
        System.out.println();
        System.out.println("Usage:  java -jar java-zenoh-publisher-<ver>-fat.jar [flags]");
        System.out.println();
        System.out.println("Flags:");
        System.out.println("  --endpoint=<proto/host:port>   Zenoh router endpoint (e.g. tcp/localhost:7447)");
        System.out.println("                                 protos: tcp, udp, ws (plain) | tls, quic, wss (TLS)");
        System.out.println("                                 If omitted, peer discovery is used.");
        System.out.println("  --key=<keyExpr>                Base key expression (default demo/example/zenoh-java)");
        System.out.println("  --org=<prefix>                 Optional per-tenant prefix prepended to --key");
        System.out.println("  --sub=<subKey>                 Optional per-sub key appended as base/<sub>");
        System.out.println("  --message=<text>               Payload text (default \"hello from java-zenoh-publisher\")");
        System.out.println("  --rate=<hz>                    Publish rate in Hz (default 1)");
        System.out.println("  --count=<n>                    Stop after n publishes (default 0 = forever)");
        System.out.println("  --stdin                        Read stdin lines and publish each as one payload");
        System.out.println();
        System.out.println("TLS / mTLS flags (require endpoint proto tls/quic/wss):");
        System.out.println("  --root-ca=<path>               Root CA certificate PEM");
        System.out.println("  --client-cert=<path>           Client certificate PEM (enables mTLS with --client-key)");
        System.out.println("  --client-key=<path>            Client private key PEM");
        System.out.println("  --verify-hostname=true|false   Verify router hostname against SAN/CN (default false)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar ... --endpoint=tcp/localhost:7447 --key=demo/example --rate=10");
        System.out.println("  java -jar ... --endpoint=tls/router:7447 --root-ca=ca.pem \\");
        System.out.println("                --client-cert=c.pem --client-key=c.key --verify-hostname=true");
    }
}
