import io.mdudel.zenoh.ZenohClient;

import java.nio.charset.StandardCharsets;

/**
 * Publisher over TLS with mutual authentication (mTLS).
 *
 * <p>Enables mTLS by passing both a client cert and a client private key
 * to the builder. Zenoh's {@code enable_mtls} flag is derived from
 * "both present" &mdash; if you pass only one, the router will still
 * see a TLS handshake but reject it, and you'll get the loud
 * {@code WARNING} in the {@link ZenohClient} startup log.</p>
 *
 * <p>Run:</p>
 * <pre>
 * mvn -q package
 * javac -cp target/java-zenoh-publisher-0.1.0-fat.jar samples/MtlsPublisher.java -d /tmp/samples-out
 * java  -cp target/java-zenoh-publisher-0.1.0-fat.jar:/tmp/samples-out \
 *       MtlsPublisher tls/router.example.com:7447 my/key \
 *                     /etc/pki/ca.pem /etc/pki/client.pem /etc/pki/client.key
 * </pre>
 */
public final class MtlsPublisher {

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("usage: MtlsPublisher <endpoint> <keyExpr> "
                    + "<rootCa> <clientCert> <clientKey> [verifyHostname]");
            System.err.println("example: MtlsPublisher tls/router:7447 my/key "
                    + "/etc/pki/ca.pem /etc/pki/me.pem /etc/pki/me.key true");
            System.exit(2);
        }
        String endpoint       = args[0];
        String key            = args[1];
        String rootCa         = args[2];
        String clientCert     = args[3];
        String clientKey      = args[4];
        boolean verifyHostname = args.length > 5 && Boolean.parseBoolean(args[5]);

        try (ZenohClient client = ZenohClient.builder()
                .connectEndpoint(endpoint)      // must be tls/ quic/ or wss/
                .keyExpr(key)
                .rootCaCertPath(rootCa)         // trust the router
                .clientCertPath(clientCert)     // present a client cert ->
                .clientKeyPath(clientKey)       //   ... which turns mTLS on
                .verifyHostname(verifyHostname) // strict SAN/CN check
                .build()) {

            client.start();

            byte[] payload = "secure hello".getBytes(StandardCharsets.UTF_8);
            client.publish(payload);
            System.out.println("[MtlsPublisher] published " + payload.length
                    + "B to key=" + key + " via " + endpoint);
        }
    }
}
