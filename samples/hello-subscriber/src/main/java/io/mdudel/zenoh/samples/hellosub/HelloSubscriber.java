package io.mdudel.zenoh.samples.hellosub;

import io.zenoh.Config;
import io.zenoh.Session;
import io.zenoh.Zenoh;
import io.zenoh.handlers.Callback;
import io.zenoh.keyexpr.KeyExpr;
import io.zenoh.pubsub.Subscriber;
import io.zenoh.sample.Sample;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

/**
 * Hello-world native Zenoh subscriber.
 *
 * <p>Companion to {@link HelloPublisher}. Deliberately does NOT use
 * {@code ZenohClient} because that class is publisher-only &mdash; the
 * point of this sample is to show the ~60 lines you'd write if you
 * wanted to extend the starter kit with a subscriber. Notice the
 * session/config bootstrap is identical to
 * {@code ZenohClient.start()}; only the {@code declarePublisher} call
 * is swapped for {@code declareSubscriber}.</p>
 *
 * <p>Run it:</p>
 * <pre>
 * # from the repo root, install the starter into ~/.m2 once:
 * mvn -f pom.xml install
 *
 * # then build and run this sample:
 * cd samples/hello-subscriber
 * mvn package
 * java -jar target/hello-subscriber-0.1.0-fat.jar
 * # then in another shell, run HelloPublisher
 * </pre>
 *
 * <p>Args: {@code endpoint} {@code keyExpr} &mdash; e.g.
 * {@code tcp/router:7447 demo/**} to subscribe to a wildcard pattern.</p>
 */
public final class HelloSubscriber {

    public static void main(String[] args) throws Exception {
        String endpoint = args.length > 0 ? args[0] : "tcp/localhost:7447";
        String keyExpr  = args.length > 1 ? args[1] : "demo/**";

        // Same config recipe ZenohClient.start() uses. Extract into a helper
        // in your own code once you have more than one primitive to declare.
        Config config = Config.loadDefault();
        if (!endpoint.isEmpty()) {
            config.insertJson5("mode", "\"client\"");
            String escaped = endpoint.replace("\"", "\\\"");
            config.insertJson5("connect/endpoints", "[\"" + escaped + "\"]");
        }

        CountDownLatch stopSignal = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(stopSignal::countDown));

        try (Session session = Zenoh.open(config)) {
            KeyExpr ke = KeyExpr.tryFrom(keyExpr);

            // Callback fires on the Zenoh IO thread -- keep it cheap.
            Callback<Sample> onSample = sample -> {
                String body = new String(sample.getPayload().toBytes(), StandardCharsets.UTF_8);
                System.out.println("[HelloSubscriber] "
                        + sample.getKeyExpr() + " -> " + body);
            };

            try (Subscriber sub = session.declareSubscriber(ke, onSample)) {
                System.out.println("[HelloSubscriber] listening key=" + keyExpr
                        + " via " + endpoint + " (Ctrl-C to stop)");
                stopSignal.await();
            }
        }

        System.out.println("[HelloSubscriber] stopped");
    }
}
