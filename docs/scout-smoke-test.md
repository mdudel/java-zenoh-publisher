# Scouting smoke test (pure-Java)

Manual end-to-end verification that `PureJavaZenohScout` discovers
real routers/peers on a local network segment. Sibling to
[`mtls-smoke-test.md`](./mtls-smoke-test.md) which verifies session-
level interop.

Unlike the mTLS test, this one never opens a TCP/TLS session and
does not touch a router's data plane. It only verifies the scouting
control plane: does the scout join the multicast group, does it hear
HELLOs, does it send valid SCOUTs.

## What we're verifying

- The scout can bind `224.0.0.224:7446` on a real NIC.
- SCOUT frames it emits are decoded by production `zenohd` (proven
  when zenohd responds with a HELLO instead of dropping the frame).
- HELLO frames from production `zenohd` decode cleanly (no
  `malformed` counter increments).
- The DiscoveredNode registry converges: single HELLO fires
  `onDiscover`, subsequent HELLOs fire `onUpdate`, silent nodes get
  swept out after `staleTimeoutMs`.

## What we are NOT verifying

- Session-level interop (that's `mtls-smoke-test.md`).
- Multicast delivery across L3 routers / VPNs — scouting is L2-scoped
  by default. Testing across subnets means testing your network's
  multicast routing, not the scout.
- Gossip discovery (peers-forwarding-peers) — scouting is direct
  multicast only; the scout doesn't parse gossip.

## Prerequisites

- One machine (call it `hostA`) with:
  - JDK 17+
  - Real (non-loopback) NIC with multicast enabled
  - Firewall allowing inbound UDP on port 7446
- A running Zenoh router (`zenohd` or the JNI one from this repo)
  on the same L2 segment. Can be `hostA` itself if hostA has a real
  NIC.

If you only have a single host with no VPN/LAN NIC (a bare cloud
VM, a locked-down container), scouting can't work at all and this
test doesn't apply.

## Setup

Build the sample:

```bash
cd /path/to/java-zenoh-publisher
mvn -f pure-java/pom.xml install
cd samples/pure-java-scout
mvn package
```

Start a router. Any of:

```bash
# Native zenohd
zenohd

# JNI router from this repo
java -jar samples/hello-publisher/target/hello-publisher-1.2.jar \
     --route --listen tcp/0.0.0.0:7447

# Docker
docker run --network=host eclipse/zenoh:latest
```

The important part: the router should log something like
`Starting Scout` or `Listening on multicast group 224.0.0.224:7446`
in its startup. That confirms it's advertising via HELLO.

## Test 1: Passive discovery

Verifies zenohd's periodic multicast HELLO advertisements decode
without touching them:

```bash
java -jar samples/pure-java-scout/target/pure-java-scout-0.1.0.jar \
     --mode=passive --duration-s=15
```

**Expected**: within a few seconds you see the router as a row in
the table:

```
=== pure-java-scout | ... | nodes=1 | scouts=0 | hellos=N ===

ZID                                 ROLE     LAST(ms)  LOCATORS / SOURCE
--------------------------------------------------------------------------------
<router-zid>                        router   <small>   tcp/<router-ip>:7447
```

Note `scouts=0` (passive) and `hellos>0`. `malformed=0` is critical
— any non-zero malformed count means our HELLO decoder disagrees
with the wire.

## Test 2: Active discovery

Verifies our SCOUT frames get through and zenohd replies:

```bash
# Optional: stop the router's periodic HELLO advertising so we prove
# our SCOUT is what triggers the reply.  On zenohd:
#   zenohd --config <cfg-with-scouting/multicast/autoconnect: []>
# (see zenohd docs; skip this step if you're just after any signal)

java -jar samples/pure-java-scout/target/pure-java-scout-0.1.0.jar \
     --mode=active --interval-ms=1000 --duration-s=10
```

**Expected**: `scouts=N` (roughly duration/interval) and `hellos>=N`
in the shutdown line, with the router in the table.

## Test 3: JSON stream mode

Verifies the machine-readable path (useful for monitoring pipelines
and UIs):

```bash
java -jar samples/pure-java-scout/target/pure-java-scout-0.1.0.jar \
     --json --duration-s=10 | tee scout-events.jsonl
jq . scout-events.jsonl | head -20
```

**Expected**: at least one `{"event":"discover", ...}` object, then
`update` objects at your interval, and an `expire` object if you
stop the router before the scout exits.

## Test 4: Role filter

Verifies the `WhatAmIMatcher` filter is applied on both the wire and
the ingestion path:

```bash
# Should discover NOTHING because we're only asking for peers and
# there aren't any (only a router):
java -jar samples/pure-java-scout/target/pure-java-scout-0.1.0.jar \
     --roles=peer --duration-s=10
```

**Expected**: `nodes=0` at exit. Then repeat with `--roles=router`
and see the router appear.

## Test 5: Multi-router mesh (optional)

If you have more than one router on the same LAN, run all of them
and confirm the scout lists every one distinctly, each with its own
ZID. Kill one router and confirm within `staleTimeoutMs` (default
15s) it disappears from the table and an `expire` JSON event fires.

## Troubleshooting

| Symptom                                    | Cause / Fix                                                                                          |
|--------------------------------------------|------------------------------------------------------------------------------------------------------|
| `no multicast-capable NIC found`           | Container/host has no real NIC. Run on a VM/bare host with a proper LAN NIC or add `--host` net.     |
| `Kernel refused multicast group join`      | Container blocks IGMP. Try `--net=host` on Docker, `hostNetwork: true` on K8s pods.                  |
| Scout runs but `hellos=0`                  | Router not on same L2 segment; firewall (Windows especially); router not actually advertising.       |
| Scout runs, `hellos=0`, router on same host| 127.0.0.1 doesn't carry multicast. Bind both to the same real NIC via `--nic=<name>`.                |
| `malformed>0`                              | Codec disagreement with server. Capture with Wireshark filter `udp.port==7446` and report the pcap.  |
| Router log shows our SCOUT but no reply    | Router's `scouting/multicast/enabled` may be false in its config; check `zenohd --config`.           |

## Success criteria

All of the following hold after Test 1 + Test 2:

- [ ] Router appears in the scout table within 5 seconds.
- [ ] `role` column matches what you started (`router` / `peer`).
- [ ] `LOCATORS` column shows the router's actual listen endpoints
      (not `(implicit: ...)` — implicit means the HELLO had L=0 and
      you're seeing the source address, still valid but less useful).
- [ ] `malformed=0` in the shutdown line.
- [ ] `scouts>0` in active mode; `scouts=0` in passive mode.
- [ ] Killing the router causes an `expire` event within
      `staleTimeoutMs` seconds.
