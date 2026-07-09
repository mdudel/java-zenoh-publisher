package io.mdudel.zenoh.samples.hellopub;

import io.mdudel.zenoh.ZenohClient;

import java.nio.charset.StandardCharsets;

/**
 * Hello-world native Zenoh publisher.
 *
 * <p>Absolute minimum: open a session to a router, declare a publisher on
 * one key expression, {@code put} one payload, close. That's it &mdash;
 * every other sample in this folder is a variation on this shape.</p>
 *
 * <p>Run it:</p>
 * <pre>
 * cd samples/hello-publisher
 * mvn package
 * java -jar target/hello-publisher-0.1.0.jar
 * </pre>
 *
 * <p>Point at your own router with two positional args
 * ({@code endpoint} then {@code keyExpr}):</p>
 * <pre>
 * java -jar target/hello-publisher-0.1.0.jar tcp/router.local:7447 my/key
 * </pre>
 *
 * <p>Verify on the subscriber side (any Zenoh subscriber works, e.g.
 * {@code z_sub -k 'demo/greeting'}, or {@link HelloSubscriber} from
 * this folder).</p>
 */
public final class HelloPublisher {

    public static void main(String[] args) throws Exception {
        String endpoint = args.length > 0 ? args[0] : "tcp/localhost:7447";
        String key      = args.length > 1 ? args[1] : "demo/greeting";

        // try-with-resources: stop() runs even if publish() throws.
        try (ZenohClient client = ZenohClient.builder()
                .connectEndpoint(endpoint)
                .keyExpr(key)
                .build()) {

            client.start();

            byte[] payload = "hello, zenoh from java!".getBytes(StandardCharsets.UTF_8);
            client.publish(payload);

            System.out.println("[HelloPublisher] published "
                    + payload.length + "B to key=" + key
                    + " via " + endpoint);
        }
    }
}
