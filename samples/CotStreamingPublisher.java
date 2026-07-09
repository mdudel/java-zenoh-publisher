import io.mdudel.zenoh.ZenohClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Streaming publisher demo.
 *
 * <p>Establishes a single {@link ZenohClient} and starts a background
 * scheduler that ticks at {@code --rate} Hz (per track). Every tick,
 * each simulated track advances one step around its elliptical path,
 * a small CoT 2.0 XML event is built, and the payload is published to
 * a per-track sub-key ({@code baseKey/<uid>}) using the publisher
 * cache exposed by {@link ZenohClient#publish(String, byte[])}.</p>
 *
 * <p>The publisher runs for {@code --ttl-seconds}, then shuts the
 * scheduler down cleanly, closes the Zenoh session, and exits with
 * a summary. A Ctrl-C also triggers the same clean shutdown.</p>
 *
 * <p>All simulation is dependency-free (uses only {@link java.util.Random}
 * and standard {@link Math}) and lives in a handful of tiny nested
 * classes so the whole file reads top-to-bottom.</p>
 *
 * <h2>Path model</h2>
 * <p>Each track owns:</p>
 * <ul>
 *   <li>A random centre {@code (lat, lon)} within a bounding box (default
 *       roughly around central Europe; override with {@code --centre-lat}
 *       / {@code --centre-lon} / {@code --span-deg}).</li>
 *   <li>Random ellipse semi-axes in metres, converted to degrees using a
 *       spherical earth approximation
 *       ({@code lat-deg-per-m = 1 / 111_320}, {@code lon-deg-per-m
 *       = 1 / (111_320 * cos(lat))}). Fine for a demo; use a real
 *       geodesy library for production.</li>
 *   <li>A random initial phase and angular velocity (rad/tick), with a
 *       50/50 clockwise/counter-clockwise flip.</li>
 *   <li>A random cruise altitude and a small altitude oscillation so
 *       the {@code hae} field doesn't sit constant.</li>
 * </ul>
 *
 * <h2>CoT payload</h2>
 * <p>Each publish is a minimal CoT 2.0 event:</p>
 * <pre>
 * &lt;?xml version="1.0" encoding="UTF-8" standalone="yes"?&gt;
 * &lt;event version="2.0" uid="TRK-0007" type="a-f-G-U-C"
 *        time="..." start="..." stale="..." how="m-g"&gt;
 *   &lt;point lat="..." lon="..." hae="..." ce="9999999.0" le="9999999.0"/&gt;
 *   &lt;detail&gt;&lt;contact callsign="TRK-0007"/&gt;&lt;/detail&gt;
 * &lt;/event&gt;
 * </pre>
 * <p>{@code type="a-f-G-U-C"} is a common public CoT type code
 * (friendly ground unit combat). Change it for your own scenario.
 * {@code ce} and {@code le} are the CoT sentinel values meaning
 * "position error unknown".</p>
 *
 * <h2>CLI</h2>
 * <pre>
 * mvn -q package
 * javac -cp target/java-zenoh-publisher-0.1.0-fat.jar samples/CotStreamingPublisher.java -d /tmp/samples-out
 * java  -cp target/java-zenoh-publisher-0.1.0-fat.jar:/tmp/samples-out \
 *       CotStreamingPublisher \
 *         --endpoint=tcp/localhost:7447 \
 *         --key=demo/cot \
 *         --tracks=5 \
 *         --rate=2 \
 *         --ttl-seconds=30
 * </pre>
 *
 * <p>Flags:</p>
 * <ul>
 *   <li>{@code --endpoint} Zenoh router endpoint (default {@code tcp/localhost:7447})</li>
 *   <li>{@code --key} base key (default {@code demo/cot}); each track publishes to {@code &lt;key&gt;/&lt;uid&gt;}</li>
 *   <li>{@code --tracks} number of tracks to simulate (default 3, max 1000)</li>
 *   <li>{@code --rate} publishes per second per track (default 1.0)</li>
 *   <li>{@code --ttl-seconds} runtime in seconds, then clean shutdown (default 30)</li>
 *   <li>{@code --centre-lat} centre latitude of random-start bounding box (default 50.0)</li>
 *   <li>{@code --centre-lon} centre longitude of random-start bounding box (default 10.0)</li>
 *   <li>{@code --span-deg} bounding box side length in degrees (default 2.0)</li>
 *   <li>{@code --seed} RNG seed for repeatable runs (default: current millis)</li>
 * </ul>
 */
public final class CotStreamingPublisher {

    // -- config ---------------------------------------------------------

    private static final class Config {
        String endpoint     = "tcp/localhost:7447";
        String baseKey      = "demo/cot";
        int    tracks       = 3;
        double rateHz       = 1.0;    // per track
        int    ttlSeconds   = 30;
        double centreLat    = 50.0;
        double centreLon    = 10.0;
        double spanDeg      = 2.0;
        long   seed         = System.currentTimeMillis();

        static Config parse(String[] args) {
            Config c = new Config();
            for (String a : args) {
                String k = a, v = "true";
                if (a.startsWith("--")) k = a.substring(2);
                else if (a.startsWith("-")) k = a.substring(1);
                int eq = k.indexOf('=');
                if (eq >= 0) { v = k.substring(eq + 1); k = k.substring(0, eq); }
                k = k.toLowerCase(Locale.ROOT);
                switch (k) {
                    case "endpoint":    c.endpoint = v; break;
                    case "key":         c.baseKey = v; break;
                    case "tracks":      c.tracks = clamp(parseInt(v, c.tracks), 1, 1000); break;
                    case "rate":        c.rateHz = Math.max(0.01, parseDouble(v, c.rateHz)); break;
                    case "ttl-seconds": c.ttlSeconds = Math.max(1, parseInt(v, c.ttlSeconds)); break;
                    case "centre-lat":  c.centreLat = parseDouble(v, c.centreLat); break;
                    case "centre-lon":  c.centreLon = parseDouble(v, c.centreLon); break;
                    case "span-deg":    c.spanDeg = Math.max(0.001, parseDouble(v, c.spanDeg)); break;
                    case "seed":        c.seed = parseLong(v, c.seed); break;
                    case "h": case "help": printUsage(); System.exit(0); break;
                    default: System.err.println("[CotStreamingPublisher] unknown flag: " + a); break;
                }
            }
            return c;
        }
    }

    // -- main -----------------------------------------------------------

    public static void main(String[] args) throws Exception {
        Config cfg = Config.parse(args);
        System.out.println("[CotStreamingPublisher] endpoint=" + cfg.endpoint
                + " key=" + cfg.baseKey
                + " tracks=" + cfg.tracks
                + " rate=" + cfg.rateHz + " Hz/track"
                + " ttl=" + cfg.ttlSeconds + "s"
                + " centre=(" + cfg.centreLat + "," + cfg.centreLon + ")"
                + " span=" + cfg.spanDeg + " deg"
                + " seed=" + cfg.seed);

        Random rng = new Random(cfg.seed);
        List<Track> tracks = new ArrayList<>(cfg.tracks);
        for (int i = 0; i < cfg.tracks; i++) {
            tracks.add(Track.random(i, cfg, rng));
        }

        // One shared ZenohClient. Publishes come from the scheduler thread
        // below; per-subkey Publisher instances are lazily created and
        // cached inside ZenohClient, so the same 5-line snippet scales
        // from 1 to 1000 tracks with no extra plumbing.
        try (ZenohClient client = ZenohClient.builder()
                .connectEndpoint(cfg.endpoint)
                .keyExpr(cfg.baseKey)
                .build()) {

            client.start();

            AtomicLong sent = new AtomicLong(0);
            AtomicLong failed = new AtomicLong(0);
            long tickPeriodNanos = Math.max(1L, (long) (1_000_000_000.0 / cfg.rateHz));

            CountDownLatch stopSignal = new CountDownLatch(1);
            ScheduledExecutorService pool = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cot-stream-publisher");
                t.setDaemon(true);      // don't hold JVM open after main returns
                return t;
            });

            // The streaming thread: fires once per tick and publishes one
            // payload per track. Runs on the scheduler pool thread; the
            // pool itself is single-threaded so publishes are serialised.
            Runnable tick = () -> {
                long now = System.currentTimeMillis();
                for (Track tr : tracks) {
                    tr.advance();
                    String xml = tr.toCot(now);
                    try {
                        client.publish(tr.uid, xml.getBytes(StandardCharsets.UTF_8));
                        sent.incrementAndGet();
                    } catch (Exception e) {
                        failed.incrementAndGet();
                        // Rate-limit the error print so a dead router doesn't spam.
                        if (failed.get() == 1 || failed.get() % 50 == 0) {
                            System.err.println("[CotStreamingPublisher] publish "
                                    + tr.uid + " failed: " + e.getMessage());
                        }
                    }
                }
            };

            pool.scheduleAtFixedRate(tick, 0, tickPeriodNanos, TimeUnit.NANOSECONDS);

            // TTL timer: fires once, signals main to stop.
            pool.schedule(stopSignal::countDown, cfg.ttlSeconds, TimeUnit.SECONDS);

            // Shutdown hook: Ctrl-C also stops us cleanly.
            Thread mainThread = Thread.currentThread();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                stopSignal.countDown();
                try { mainThread.join(2_000); } catch (InterruptedException ignored) {}
            }, "cot-shutdown"));

            System.out.println("[CotStreamingPublisher] streaming started"
                    + " (period=" + String.format(Locale.ROOT, "%.3f", tickPeriodNanos / 1_000_000.0)
                    + " ms per tick, " + tracks.size() + " tracks/tick)");

            stopSignal.await();

            // Clean shutdown: stop new ticks, wait for the in-flight tick
            // to finish, then let ZenohClient.close() run via the
            // try-with-resources.
            pool.shutdown();
            if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }

            System.out.println("[CotStreamingPublisher] stopping after "
                    + cfg.ttlSeconds + "s: sent=" + sent.get()
                    + " failed=" + failed.get());
        }
    }

    // -- Track model ----------------------------------------------------

    /**
     * One simulated track walking around an ellipse.
     *
     * <p>Ellipse maths in local tangent-plane approximation: convert
     * metres to lat/lon degree offsets using
     * {@code 1 deg lat = 111 320 m} and
     * {@code 1 deg lon = 111 320 * cos(lat) m}. This is good to
     * within a fraction of a percent over a few km of extent, which
     * is all a demo needs.</p>
     */
    private static final class Track {
        // stable
        final String uid;
        final double centreLat, centreLon;
        final double semiMajorM, semiMinorM;
        final double rotationRad;    // ellipse orientation
        final double angVelRad;      // rad per tick, signed
        final double cruiseAltM;
        final double altAmplitudeM;
        final double altPhase;

        // mutable
        double phaseRad;

        private Track(String uid, double lat, double lon,
                      double semiMajorM, double semiMinorM,
                      double rotationRad, double angVelRad,
                      double cruiseAltM, double altAmplitudeM,
                      double altPhase, double initialPhase) {
            this.uid = uid;
            this.centreLat = lat;
            this.centreLon = lon;
            this.semiMajorM = semiMajorM;
            this.semiMinorM = semiMinorM;
            this.rotationRad = rotationRad;
            this.angVelRad = angVelRad;
            this.cruiseAltM = cruiseAltM;
            this.altAmplitudeM = altAmplitudeM;
            this.altPhase = altPhase;
            this.phaseRad = initialPhase;
        }

        static Track random(int index, Config cfg, Random rng) {
            String uid = String.format(Locale.ROOT, "TRK-%04d", index);
            double half = cfg.spanDeg / 2.0;
            double lat = cfg.centreLat + (rng.nextDouble() * 2 - 1) * half;
            double lon = cfg.centreLon + (rng.nextDouble() * 2 - 1) * half;
            double semiMajorM = 500 + rng.nextDouble() * 4_500;    // 0.5 - 5 km
            double semiMinorM = semiMajorM * (0.3 + rng.nextDouble() * 0.6); // 30 - 90% of major
            double rotation   = rng.nextDouble() * 2 * Math.PI;
            // Angular velocity: one revolution every 30 s to 5 min at 1 Hz,
            // signed (CW or CCW).
            double periodSec  = 30 + rng.nextDouble() * 270;
            double angVel     = (2 * Math.PI / periodSec) / cfg.rateHz;
            if (rng.nextBoolean()) angVel = -angVel;
            double cruiseAlt  = 100 + rng.nextDouble() * 9_900;      // 100 m - 10 km
            double altAmp     = 20 + rng.nextDouble() * 480;         // 20 - 500 m
            double altPhase   = rng.nextDouble() * 2 * Math.PI;
            double initPhase  = rng.nextDouble() * 2 * Math.PI;
            return new Track(uid, lat, lon, semiMajorM, semiMinorM,
                    rotation, angVel, cruiseAlt, altAmp, altPhase, initPhase);
        }

        void advance() {
            phaseRad += angVelRad;
            altPhaseAdvance();
        }

        // separate so the alt frequency can be tuned independently later
        private double altOscPhase = 0.0;
        private void altPhaseAdvance() { altOscPhase += 0.05; }

        /** Current position: (lat, lon, altMetres). */
        double[] position() {
            // parametric ellipse in metres, then rotate
            double x = semiMajorM * Math.cos(phaseRad);
            double y = semiMinorM * Math.sin(phaseRad);
            double xr =  x * Math.cos(rotationRad) - y * Math.sin(rotationRad);
            double yr =  x * Math.sin(rotationRad) + y * Math.cos(rotationRad);
            // metres -> deg
            double metresPerDegLat = 111_320.0;
            double metresPerDegLon = 111_320.0 * Math.cos(Math.toRadians(centreLat));
            double lat = centreLat + yr / metresPerDegLat;
            double lon = centreLon + xr / metresPerDegLon;
            double alt = cruiseAltM + altAmplitudeM * Math.sin(altPhase + altOscPhase);
            return new double[] { lat, lon, alt };
        }

        String toCot(long epochMillis) {
            double[] p = position();
            String now   = ISO.format(Instant.ofEpochMilli(epochMillis));
            String stale = ISO.format(Instant.ofEpochMilli(epochMillis + 60_000L));
            return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<event version=\"2.0\""
                    + " uid=\"" + uid + "\""
                    + " type=\"a-f-G-U-C\""
                    + " time=\"" + now + "\""
                    + " start=\"" + now + "\""
                    + " stale=\"" + stale + "\""
                    + " how=\"m-g\">"
                    + "<point"
                    + " lat=\"" + fmt(p[0], 6) + "\""
                    + " lon=\"" + fmt(p[1], 6) + "\""
                    + " hae=\"" + fmt(p[2], 2) + "\""
                    + " ce=\"9999999.0\" le=\"9999999.0\"/>"
                    + "<detail><contact callsign=\"" + uid + "\"/></detail>"
                    + "</event>";
        }
    }

    // -- helpers --------------------------------------------------------

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

    private static int    parseInt(String s, int def)    { try { return Integer.parseInt(s); } catch (Exception e) { return def; } }
    private static long   parseLong(String s, long def)  { try { return Long.parseLong(s); } catch (Exception e) { return def; } }
    private static double parseDouble(String s, double def) { try { return Double.parseDouble(s); } catch (Exception e) { return def; } }
    private static int    clamp(int v, int lo, int hi)   { return Math.max(lo, Math.min(hi, v)); }
    private static String fmt(double v, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", v);
    }

    private static void printUsage() {
        System.out.println("Usage: CotStreamingPublisher [flags]");
        System.out.println("  --endpoint=<proto/host:port>  Zenoh router endpoint (default tcp/localhost:7447)");
        System.out.println("  --key=<keyExpr>               Base key expr; per-track sub-key appended as <key>/<uid>");
        System.out.println("                                (default demo/cot)");
        System.out.println("  --tracks=<n>                  Number of simulated tracks (default 3, max 1000)");
        System.out.println("  --rate=<hz>                   Publish rate per track (default 1.0 Hz)");
        System.out.println("  --ttl-seconds=<n>             Run duration before clean shutdown (default 30)");
        System.out.println("  --centre-lat=<deg>            Bounding-box centre latitude (default 50.0)");
        System.out.println("  --centre-lon=<deg>            Bounding-box centre longitude (default 10.0)");
        System.out.println("  --span-deg=<deg>              Bounding-box side length in degrees (default 2.0)");
        System.out.println("  --seed=<long>                 RNG seed for repeatable runs (default: current millis)");
    }
}
