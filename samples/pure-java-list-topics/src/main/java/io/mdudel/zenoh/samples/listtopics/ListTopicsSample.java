/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.samples.listtopics;

import io.mdudel.zenoh.purejava.PureJavaZenohSubscriber;
import io.mdudel.zenoh.purejava.PureJavaZenohSubscriber.Topic;

import java.util.List;

/**
 * One-shot Zenoh topic-lister built on the pure-Java client. Connects
 * to a router, asks for its current set of subscriber declarations
 * matching a key-expression pattern, prints them as either a plain
 * table or a compact JSON array, then exits.
 *
 * <p>Positional args (all optional):</p>
 * <pre>
 * java -jar target/pure-java-list-topics-0.1.0.jar
 *   [endpoint]         default: tcp/localhost:7447
 *   [keyExprPattern]   default: **      (see key-expression cheat-sheet)
 *   [timeoutMs]        default: 3000    (upper bound on router reply time)
 * </pre>
 *
 * <p>Flags:</p>
 * <pre>
 *   --json                emit compact JSON to stdout instead of a table
 *   --help                print usage and exit
 * </pre>
 *
 * <p>Caveat: routers only advertise <b>subscriber</b> declarations to
 * scouts / interest-holders. Zenoh 1.x has no {@code DeclarePublisher}
 * counterpart, so key expressions that have publishers but no
 * subscribers do not show up in this listing. If you need to catch
 * publishers directly, subscribe to {@code **} and observe
 * {@link io.mdudel.zenoh.purejava.session.Sample#key()} on incoming
 * samples instead.</p>
 */
public final class ListTopicsSample {

    public static void main(String[] args) throws Exception {
        // Flag parsing first so positional args can still hold their positions.
        boolean asJson = false;
        int i;
        for (i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--help".equals(a) || "-h".equals(a)) {
                printUsage();
                return;
            } else if ("--json".equals(a)) {
                asJson = true;
            } else {
                break; // first non-flag arg -- rest are positional
            }
        }

        String[] rest = new String[args.length - i];
        System.arraycopy(args, i, rest, 0, rest.length);

        String endpoint       = rest.length > 0 ? rest[0] : "tcp/localhost:7447";
        String keyExprPattern = rest.length > 1 ? rest[1] : "**";
        long   timeoutMs      = rest.length > 2 ? Long.parseLong(rest[2]) : 3_000L;

        if (!asJson) {
            System.out.println("[pure-java-list-topics] endpoint=" + endpoint
                    + " pattern=" + keyExprPattern
                    + " timeout=" + timeoutMs + "ms");
        }

        try (PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint(endpoint)
                .build()) {

            sub.start();
            List<Topic> topics = sub.listTopicsNow(keyExprPattern, timeoutMs);

            if (asJson) {
                // Emit ONLY the JSON to stdout so it can be piped to jq / a file /
                // an HTTP response. No banner, no trailing newline noise.
                System.out.println(Topic.toJson(topics));
            } else {
                printTable(topics);
            }
        }
    }

    private static void printTable(List<Topic> topics) {
        if (topics.isEmpty()) {
            System.out.println("[pure-java-list-topics] no matching subscribers on this router");
            return;
        }
        // Simple two-column table, right-padded key expr for readability.
        int maxKeyLen = "KEY EXPRESSION".length();
        for (Topic t : topics) {
            if (t.keyExpr().length() > maxKeyLen) maxKeyLen = t.keyExpr().length();
        }
        String fmt = "%-" + maxKeyLen + "s   %s%n";
        System.out.println();
        System.out.printf(fmt, "KEY EXPRESSION", "DECLARED-BY-ID");
        System.out.println("-".repeat(maxKeyLen + 3 + "DECLARED-BY-ID".length()));
        for (Topic t : topics) {
            System.out.printf(fmt, t.keyExpr(), t.declaredById());
        }
        System.out.println();
        System.out.println("[pure-java-list-topics] " + topics.size()
                + " topic" + (topics.size() == 1 ? "" : "s"));
    }

    private static void printUsage() {
        System.out.println("""
                pure-java-list-topics [flags] [endpoint] [keyExprPattern] [timeoutMs]

                Flags:
                  --json          emit compact JSON to stdout instead of a table
                  -h, --help      show this help and exit

                Positional args (all optional):
                  endpoint        default: tcp/localhost:7447
                  keyExprPattern  default: **
                  timeoutMs       default: 3000

                Examples:
                  # Table of every subscriber known to the local router
                  pure-java-list-topics

                  # JSON for a specific pattern, ready to pipe into jq
                  pure-java-list-topics --json tcp/router.local:7447 'sensors/**'

                  # Longer wait if the router is slow to reply
                  pure-java-list-topics tcp/router.local:7447 '**' 10000

                Only subscriber declarations are reported. Zenoh 1.x has no
                DeclarePublisher wire message, so publishers without a matching
                subscriber are invisible here.
                """);
    }
}
