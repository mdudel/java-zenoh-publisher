# pure-java-scout

Minimum-viable pure-Java Zenoh scouting / node-discovery sample. Uses
`io.mdudel.zenoh.purejava.scouting.PureJavaZenohScout` to listen for
Zenoh HELLO messages on the local multicast segment, optionally
emit SCOUT queries, and print discovered nodes / routers.

**Zero third-party runtime dependencies.** JDK 17 only. **No TCP
session ever opened.** The scout is a passive/active multicast
observer — it never becomes a Zenoh peer, router or client, never
sends INIT, and never appears as a session on the routers it finds.

Sibling of [`pure-java-simple-publisher/`](../pure-java-simple-publisher/)
and [`pure-java-simple-subscriber/`](../pure-java-simple-subscriber/).

## Build

The pure-Java module lives at `../../pure-java/` in this repo.
One-time install into your local `~/.m2/repository`:

```bash
# From the repo root:
mvn -f pure-java/pom.xml install
```

> **After a `git pull`**: re-run the above `mvn install` before rebuilding
> this sample. Otherwise you'll get
> `cannot find symbol: class PureJavaZenohScout` when the pure-Java
> module has grown a new public class since your last install — the
> .m2 jar is invisible and goes stale silently.

Then this sample:

```bash
cd samples/pure-java-scout
mvn package
```

Produces `target/pure-java-scout-0.1.0.jar` — a runnable fat jar
around 160 KB (drags in the codec + session code from the pure-java
module even though scouting itself doesn't touch them; the shade
plugin doesn't do tree-shaking).

## Run

```bash
# Default: active mode, listen + emit SCOUTs every 3s, live table until Ctrl-C
java -jar target/pure-java-scout-0.1.0.jar

# Passive mode: never emit SCOUTs, only listen to auto-advertising nodes
java -jar target/pure-java-scout-0.1.0.jar --mode=passive

# Discover routers only
java -jar target/pure-java-scout-0.1.0.jar --roles=router

# JSON output, one event per line (for piping to jq or a monitoring pipeline)
java -jar target/pure-java-scout-0.1.0.jar --json | jq .

# Single-shot: wait for one discovery, print the snapshot, exit
java -jar target/pure-java-scout-0.1.0.jar --once --duration-s=5

# Bind to a specific NIC (WireGuard, wg0, utun0, etc.)
java -jar target/pure-java-scout-0.1.0.jar --nic=eth0

# Aggressive scanning, tight stale window
java -jar target/pure-java-scout-0.1.0.jar --interval-ms=500 --stale-ms=3000
```

Full flag list: `java -jar target/pure-java-scout-0.1.0.jar --help`.

## Sample table output

```
=== pure-java-scout | 2026-07-12T07:03:15.412Z | nodes=2 | scouts=4 | hellos=6 ===

ZID                                 ROLE     LAST(ms)  LOCATORS / SOURCE
--------------------------------------------------------------------------------
a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4    router   431       tcp/192.168.1.10:7447, tls/router.lan:7449
9f8e7d6c5b4a9f8e7d6c5b4a9f8e7d6c    peer     1120      tcp/192.168.1.22:7447
```

## Sample JSON stream output

```
{"event":"discover","zid":"a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4","role":"router","source":"192.168.1.10:7446","version":9,"first_seen":"2026-07-12T07:03:12.401Z","last_seen":"2026-07-12T07:03:12.401Z","locators":["tcp/192.168.1.10:7447"]}
{"event":"update","zid":"a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4","role":"router","source":"192.168.1.10:7446","version":9,"first_seen":"2026-07-12T07:03:12.401Z","last_seen":"2026-07-12T07:03:15.412Z","locators":["tcp/192.168.1.10:7447","tls/router.lan:7449"]}
{"event":"expire","zid":"a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4","role":"router","source":"192.168.1.10:7446","version":9,"first_seen":"2026-07-12T07:03:12.401Z","last_seen":"2026-07-12T07:03:15.412Z","locators":["tcp/192.168.1.10:7447","tls/router.lan:7449"]}
```

Perfect for piping into `jq`, a log aggregator, or a UI's SSE stream.

## Environment gotchas

Same three that bit the ingest listener (memory of 2026-06-18) and
will absolutely bite anyone running this for the first time:

1. **127.0.0.1 does NOT carry multicast** on any OS — Linux, macOS,
   Windows all agree on this. If your router and this scout are on
   the same host, bind both to the same real NIC or VPN
   (`--nic=eth0`, `--nic=wg0`, `--nic=utun0`, etc.). Loopback is
   auto-skipped by the scout because it doesn't set the multicast
   flag.

2. **Windows firewall**: allow inbound UDP on the JVM binary
   (`java.exe`) via a firewall rule, and set the network profile to
   Private, not Public. From PowerShell as Admin:

   ```powershell
   New-NetFirewallRule -DisplayName 'Java multicast inbound' `
       -Direction Inbound -Program 'C:\path\to\java.exe' `
       -Action Allow -Protocol UDP
   ```

3. **Locked-down containers / K8s pods with /32 netmasks**: the
   kernel may refuse to join `224.0.0.224` at all. The scout logs a
   warning per failed interface and exits with `no multicast-capable
   network interfaces found` if all fail. This is not fixable inside
   the JVM — you need CNI / host networking with real multicast
   routing. The bundled `PureJavaZenohScoutIntegrationTest` uses
   JUnit `Assumptions` to skip cleanly on such hosts.

## Verify against a real router

Start any Zenoh router (the JNI one in this repo, a native `zenohd`,
or the mTLS setup from `docs/mtls-smoke-test.md`) on the same L2
segment as the scout. Within a few seconds you should see the router
in the table.

```bash
# In one terminal
zenohd

# In another
java -jar samples/pure-java-scout/target/pure-java-scout-0.1.0.jar
```

## Programmatic API

If you'd rather embed than shell out:

```java
try (PureJavaZenohScout scout = PureJavaZenohScout.builder()
        .mode(PureJavaZenohScout.Mode.ACTIVE)
        .scoutIntervalMillis(3_000)
        .staleTimeoutMillis(15_000)
        .role(WhatAmI.ROUTER)                     // discover routers only
        // .networkInterface("eth0")               // optional; else auto-detect
        .listener(new ScoutListener() {
            @Override public void onDiscover(DiscoveredNode n) {
                log.info("+ {} {} at {}", n.role(), n.zid(), n.bestLocator());
            }
            @Override public void onExpire(DiscoveredNode n) {
                log.info("- {} gone", n.zid());
            }
        })
        .build()) {

    scout.start();

    // Any time later, pull the current registry
    List<DiscoveredNode> nodes = scout.snapshot();
    for (DiscoveredNode n : nodes) {
        System.out.println(n.role() + " " + n.zid() + " " + n.locators());
    }

    // Send one extra SCOUT immediately regardless of mode
    scout.scoutNow();

    // Runs on daemon threads; close() when done
    Thread.sleep(30_000);
}
```

The callback API and the `snapshot()` pull API are always both
available on the same scout instance — pick whichever fits your UI
model, or use both together.

## What's exercised

Every successful run touches:

- `PureJavaZenohScout` facade + builder + multi-NIC socket binder
- `WhatAmIMatcher` role bitmap (SCOUT-side, disjoint from HELLO's
  WhatAmI enum encoding)
- Wire codec: `Scout.encode()` for outbound (active mode) and
  `Hello.decode()` for inbound
- `DiscoveredNode` registry, staleness sweeper, listener contract
- Idempotent `AutoCloseable.close()` in the shutdown-hook path
