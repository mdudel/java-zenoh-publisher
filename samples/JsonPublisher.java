import io.mdudel.zenoh.ZenohClient;

import java.nio.charset.StandardCharsets;

/**
 * Publish structured JSON payloads with no third-party JSON library.
 *
 * <p>Shows how to layer a serializer on top of {@link ZenohClient} without
 * modifying it. Uses hand-rolled JSON so the sample stays dependency-free
 * &mdash; in real code you'd use Jackson / Gson / whatever your project
 * already has.</p>
 *
 * <p>Also demonstrates the per-subkey publisher cache: each
 * {@code publish(subKey, bytes)} call routes to
 * {@code baseKey/subKey}, and the underlying {@link io.zenoh.pubsub.Publisher}
 * for that subKey is declared lazily and reused.</p>
 *
 * <p>Run:</p>
 * <pre>
 * mvn -q package
 * javac -cp target/java-zenoh-publisher-0.1.0-fat.jar samples/JsonPublisher.java -d /tmp/samples-out
 * java  -cp target/java-zenoh-publisher-0.1.0-fat.jar:/tmp/samples-out JsonPublisher
 * </pre>
 */
public final class JsonPublisher {

    public static void main(String[] args) throws Exception {
        String endpoint = args.length > 0 ? args[0] : "tcp/localhost:7447";
        String baseKey  = args.length > 1 ? args[1] : "demo/tracks";

        try (ZenohClient client = ZenohClient.builder()
                .connectEndpoint(endpoint)
                .keyExpr(baseKey)
                .build()) {

            client.start();

            for (int i = 0; i < 5; i++) {
                String trackId = "track-" + i;
                String json = jsonTrack(trackId, 52.5 + i * 0.01, 13.4 + i * 0.02, i * 10);
                byte[] payload = json.getBytes(StandardCharsets.UTF_8);

                // Per-subkey routing: published to baseKey/track-i
                client.publish(trackId, payload);
                System.out.println("[JsonPublisher] " + baseKey + "/" + trackId + " " + json);

                Thread.sleep(200);
            }

            System.out.println("[JsonPublisher] sent=" + client.getSentCount());
        }
    }

    /** Build one JSON object without a JSON lib. Fine for a sample; use a real
     *  serializer in production code. */
    private static String jsonTrack(String id, double lat, double lon, int speedKts) {
        return "{"
                + "\"trackId\":\"" + id + "\","
                + "\"lat\":" + lat + ","
                + "\"lon\":" + lon + ","
                + "\"speedKts\":" + speedKts + ","
                + "\"ts\":" + System.currentTimeMillis()
                + "}";
    }
}
