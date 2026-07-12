/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.samples.scout;

import io.mdudel.zenoh.purejava.scouting.DiscoveredNode;
import io.mdudel.zenoh.purejava.scouting.PureJavaZenohScout;
import io.mdudel.zenoh.purejava.scouting.ScoutListener;
import io.mdudel.zenoh.purejava.wire.WhatAmI;
import io.mdudel.zenoh.purejava.wire.WhatAmIMatcher;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Minimum-viable pure-Java Zenoh scouting sample.
 *
 * <p>Prints discovered Zenoh nodes / routers on the local multicast
 * segment. Two output modes:</p>
 * <ul>
 *   <li><b>Table</b> (default): live-updating stdout table with one row
 *       per node. Reprints once a second.</li>
 *   <li><b>JSON</b> (with {@code --json}): one JSON object per line,
 *       one line per callback event ({@code discover} / {@code update}
 *       / {@code expire}). Machine-friendly; no ANSI.</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>
 * java -jar pure-java-scout-0.1.0.jar [flags...]
 *
 *   --mode=active|passive              default: active
 *   --interval-ms=&lt;n&gt;                  active-mode SCOUT interval; default 3000
 *   --stale-ms=&lt;n&gt;                     stale timeout; default 15000
 *   --group=&lt;addr&gt;                     multicast group; default 224.0.0.224
 *   --port=&lt;n&gt;                         UDP port; default 7446
 *   --nic=&lt;name&gt;                       bind to one NIC; default auto-detect
 *   --roles=router,peer,client         filter matcher; default all three
 *   --duration-s=&lt;n&gt;                   auto-exit after n seconds; default 0 = forever
 *   --json                             one-JSON-object-per-event output mode
 *   --once                             wait for one discovery, print snapshot, exit
 * </pre>
 */
public final class ScoutSample {

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args);
        if (a.showHelp) { printHelp(); return; }

        PureJavaZenohScout.Builder b = PureJavaZenohScout.builder()
                .mode(a.mode)
                .scoutIntervalMillis(a.intervalMs)
                .staleTimeoutMillis(a.staleMs)
                .multicastAddress(a.group)
                .multicastPort(a.port)
                .whatAmIMatcher(a.matcher);
        if (a.nic != null) b.networkInterface(a.nic);

        ScoutListener listener = a.json ? new JsonListener() : new NopListener();
        b.listener(listener);

        try (PureJavaZenohScout scout = b.build()) {
            System.err.println("[pure-java-scout] starting mode=" + a.mode
                    + " interval=" + a.intervalMs + "ms group=" + a.group + ":" + a.port
                    + " roles=" + a.matcher);
            scout.start();

            if (a.once) {
                waitForFirst(scout, a.durationSeconds > 0 ? a.durationSeconds : 10);
                printTable(scout, System.out);
                return;
            }

            ScheduledExecutorService ticker = null;
            if (!a.json) {
                ticker = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "scout-sample-repaint");
                    t.setDaemon(true);
                    return t;
                });
                ticker.scheduleAtFixedRate(
                        () -> printTable(scout, System.out),
                        0L, 1L, TimeUnit.SECONDS);
            }

            long endMs = a.durationSeconds > 0
                    ? System.currentTimeMillis() + a.durationSeconds * 1000L
                    : Long.MAX_VALUE;
            // Ctrl-C or timeout ends the run.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.err.println("\n[pure-java-scout] shutting down. "
                        + "scouts=" + scout.scoutsSent()
                        + " hellos=" + scout.hellosParsed()
                        + " malformed=" + scout.hellosMalformed());
            }));
            while (System.currentTimeMillis() < endMs) {
                Thread.sleep(500L);
            }
            if (ticker != null) ticker.shutdownNow();
        }
    }

    // ---- Output modes --------------------------------------------------

    /** Table mode uses a snapshot repaint; no callback state needed. */
    private static final class NopListener implements ScoutListener {}

    /** JSON mode emits one line per event. Ordering matches callback order. */
    private static final class JsonListener implements ScoutListener {
        @Override public void onDiscover(DiscoveredNode n) { emit("discover", n); }
        @Override public void onUpdate(DiscoveredNode prev, DiscoveredNode now) { emit("update", now); }
        @Override public void onExpire(DiscoveredNode n) { emit("expire", n); }

        private static void emit(String event, DiscoveredNode n) {
            StringBuilder sb = new StringBuilder(200);
            sb.append('{');
            sb.append("\"event\":\"").append(event).append('"');
            sb.append(",\"zid\":\"").append(n.zid()).append('"');
            sb.append(",\"role\":\"").append(n.role().name().toLowerCase()).append('"');
            sb.append(",\"source\":\"").append(n.source().getHostString())
              .append(':').append(n.source().getPort()).append('"');
            sb.append(",\"version\":").append(n.protocolVersion());
            sb.append(",\"first_seen\":\"").append(n.firstSeen()).append('"');
            sb.append(",\"last_seen\":\"").append(n.lastSeen()).append('"');
            sb.append(",\"locators\":[");
            for (int i = 0; i < n.locators().size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(escape(n.locators().get(i))).append('"');
            }
            sb.append("]}");
            System.out.println(sb);
        }

        private static String escape(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    private static void printTable(PureJavaZenohScout scout, java.io.PrintStream out) {
        List<DiscoveredNode> snap = scout.snapshot();
        StringBuilder sb = new StringBuilder(1024);
        // Clear-screen + home cursor: works on ANSI terminals; PowerShell 5.1
        // is fine here (only ANSI-hostile if the user's PS host is truly
        // ancient). If output is redirected/piped, ANSI is inert bytes.
        sb.append("\u001B[2J\u001B[H");
        sb.append("=== pure-java-scout | ")
          .append(Instant.now()).append(" | nodes=").append(snap.size())
          .append(" | scouts=").append(scout.scoutsSent())
          .append(" | hellos=").append(scout.hellosParsed())
          .append(" ===\n\n");
        sb.append(String.format("%-34s  %-7s  %-8s  %s%n",
                "ZID", "ROLE", "LAST(ms)", "LOCATORS / SOURCE"));
        sb.append("--------------------------------------------------------------------------------\n");
        Instant now = Instant.now();
        for (DiscoveredNode n : snap) {
            long ageMs = Duration.between(n.lastSeen(), now).toMillis();
            String locs = n.locators().isEmpty()
                    ? "(implicit: " + n.source().getHostString() + ")"
                    : String.join(", ", n.locators());
            sb.append(String.format("%-34s  %-7s  %-8d  %s%n",
                    truncate(n.zid().toString(), 34),
                    n.role().name().toLowerCase(),
                    ageMs, locs));
        }
        if (snap.isEmpty()) {
            sb.append("(no nodes yet - waiting for HELLOs)\n");
        }
        out.print(sb);
        out.flush();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "\u2026";
    }

    private static void waitForFirst(PureJavaZenohScout scout, int maxSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (scout.snapshot().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200L);
        }
    }

    private static void printHelp() {
        System.err.println("""
                pure-java-scout - discover Zenoh nodes on the local multicast segment
                
                Usage: java -jar pure-java-scout-<version>.jar [flags...]
                
                  --mode=active|passive        default: active
                  --interval-ms=<n>            SCOUT interval; default 3000 (active only)
                  --stale-ms=<n>               stale timeout; default 15000
                  --group=<addr>               multicast group; default 224.0.0.224
                  --port=<n>                   UDP port; default 7446
                  --nic=<name>                 bind to one NIC; default auto-detect
                  --roles=router,peer,client   filter matcher; default all three
                  --duration-s=<n>             auto-exit after n seconds; default 0 = forever
                  --json                       one JSON object per event (no ANSI)
                  --once                       wait for one discovery, print snapshot, exit
                  --help                       this help
                
                Environment:
                
                  * 127.0.0.1 does NOT carry multicast on any OS. If your router and
                    scout are on the same host, bind both to the same real NIC or VPN
                    (--nic=eth0, --nic=wg0, --nic=utun0, etc.).
                  * Windows: allow inbound UDP on the JVM binary via a firewall rule;
                    network profile must be Private, not Public.
                """);
    }

    // ---- CLI parsing ---------------------------------------------------

    private static final class Args {
        PureJavaZenohScout.Mode mode = PureJavaZenohScout.Mode.ACTIVE;
        long   intervalMs      = PureJavaZenohScout.DEFAULT_SCOUT_INTERVAL_MS;
        long   staleMs         = PureJavaZenohScout.DEFAULT_STALE_TIMEOUT_MS;
        String group           = PureJavaZenohScout.DEFAULT_MULTICAST_ADDRESS;
        int    port            = PureJavaZenohScout.DEFAULT_MULTICAST_PORT;
        String nic             = null;
        WhatAmIMatcher matcher = WhatAmIMatcher.any();
        int    durationSeconds = 0;
        boolean json           = false;
        boolean once           = false;
        boolean showHelp       = false;

        static Args parse(String[] argv) {
            Args a = new Args();
            for (String s : argv) {
                if (s.equals("--help") || s.equals("-h")) { a.showHelp = true; continue; }
                if (s.equals("--json")) { a.json = true; continue; }
                if (s.equals("--once")) { a.once = true; continue; }
                if (s.startsWith("--mode=")) {
                    a.mode = PureJavaZenohScout.Mode.valueOf(val(s).toUpperCase());
                } else if (s.startsWith("--interval-ms=")) {
                    a.intervalMs = Long.parseLong(val(s));
                } else if (s.startsWith("--stale-ms=")) {
                    a.staleMs = Long.parseLong(val(s));
                } else if (s.startsWith("--group=")) {
                    a.group = val(s);
                } else if (s.startsWith("--port=")) {
                    a.port = Integer.parseInt(val(s));
                } else if (s.startsWith("--nic=")) {
                    a.nic = val(s);
                } else if (s.startsWith("--duration-s=")) {
                    a.durationSeconds = Integer.parseInt(val(s));
                } else if (s.startsWith("--roles=")) {
                    EnumSet<WhatAmI> roles = EnumSet.noneOf(WhatAmI.class);
                    for (String r : val(s).split(",")) {
                        roles.add(WhatAmI.valueOf(r.trim().toUpperCase()));
                    }
                    a.matcher = WhatAmIMatcher.of(roles);
                } else {
                    throw new IllegalArgumentException(
                            "unknown flag: " + s + " (use --help)");
                }
            }
            return a;
        }

        private static String val(String kv) {
            int eq = kv.indexOf('=');
            return kv.substring(eq + 1);
        }
    }

    private ScoutSample() {}
}
