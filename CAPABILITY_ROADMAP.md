# Zenoh Capability Assessment & Pure-Java Roadmap

Assessment of current capabilities in `java-zenoh-publisher` (specifically the zero-JNI `pure-java` module) compared against upstream [eclipse-zenoh/zenoh](https://github.com/eclipse-zenoh/zenoh), [eclipse-zenoh/zenoh-pico](https://github.com/eclipse-zenoh/zenoh-pico), and [eclipse-zenoh/zenoh-java](https://github.com/eclipse-zenoh/zenoh-java), along with a prioritized implementation plan for missing features using **pure Java (JDK 17 stdlib only, zero JNI/Rust dependencies)**.

---

## 1. Current Capabilities in `java-zenoh-publisher`

This repository contains two distinct implementations:

### A. Root Module (`io.mdudel:java-zenoh-publisher` — JNI wrapper)
- Wraps official `io.zenoh:zenoh-java:1.9.0` (JNI over Rust `zenoh`, `x86_64` native libraries only).
- [`ZenohClient`](src/main/java/io/mdudel/zenoh/ZenohClient.java): Publisher-only wrapper with endpoint connect (`tcp/`, `udp/`, `tls/`, `quic/`, `ws/`, `wss/`, or default multicast peer mode), `org` prefix resolution, JSON5 TLS/mTLS config generation, lazy per-subkey `Publisher` cache (`declarePublisher`), and basic counters (`sentCount`, `lastSendMs`, `lastError`).
- [`ZenohPublisherApp`](src/main/java/io/mdudel/zenoh/ZenohPublisherApp.java): CLI publisher entrypoint.

### B. Pure-Java Module (`io.mdudel:java-zenoh-publisher-pure` in [`pure-java/`](pure-java/README.md))
Clean-room implementation of **Zenoh 1.x wire protocol v0.9 (`0x09`)** in **JDK 17 stdlib only** (zero JNI, zero Rust, zero third-party runtime dependencies, ~60 KB JAR):
1. **Transports (`io.mdudel.zenoh.purejava.transport`)**:
   - **TCP**: [`TcpTransport`](pure-java/src/main/java/io/mdudel/zenoh/purejava/transport/TcpTransport.java) (`tcp/host:port`) with 16-bit little-endian [`StreamFramer`](pure-java/src/main/java/io/mdudel/zenoh/purejava/transport/StreamFramer.java) (max 65,535-byte transport batch).
   - **TLS / mTLS**: [`TlsTransport`](pure-java/src/main/java/io/mdudel/zenoh/purejava/transport/TlsTransport.java) (`tls/host:port`), [`TlsConfig`](pure-java/src/main/java/io/mdudel/zenoh/purejava/transport/TlsConfig.java), and [`PemLoader`](pure-java/src/main/java/io/mdudel/zenoh/purejava/transport/PemLoader.java) (TLS 1.2/1.3, server CA trust, client mTLS, PKCS#8 PEM, PKCS#1 RSA PEM, PKCS#12 `.p12`/`.pfx`, optional hostname verification).
   - **WebSocket**: [`WsTransport`](pure-java/src/main/java/io/mdudel/zenoh/purejava/transport/WsTransport.java) (`ws/host:port` and `wss/host:port` via `java.net.http.WebSocket`, one transport message per binary WS frame).
2. **Session & State Machine (`io.mdudel.zenoh.purejava.session`)**:
   - [`ZenohSession`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/ZenohSession.java): 4-message client handshake ([`Init.InitSyn`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Init.java) -> [`Init.InitAck`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Init.java) -> [`Open.OpenSyn`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Open.java) -> [`Open.OpenAck`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Open.java)), monotonic sequence numbers, periodic [`KeepAlive`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/KeepAlive.java) (`lease / 4`), lease-expiry watchdog, clean [`Close`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Close.java) teardown, and single-reader background thread.
3. **Publishing**:
   - [`PureJavaZenohPublisher`](pure-java/src/main/java/io/mdudel/zenoh/purejava/PureJavaZenohPublisher.java): Sends [`Frame`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Frame.java) -> [`Push`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Push.java) (full string suffix, `keyScope=0`) -> [`Put`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Put.java) with [`Encoding`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/Encoding.java).
4. **Subscribing & Topic Discovery**:
   - [`PureJavaZenohSubscriber`](pure-java/src/main/java/io/mdudel/zenoh/purejava/PureJavaZenohSubscriber.java) & [`Subscription`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/Subscription.java): Sends [`Declare`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Declare.java) + [`DeclareSubscriber`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/DeclareSubscriber.java) / [`UndeclareSubscriber`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/UndeclareSubscriber.java), matches inbound [`Push`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Push.java) via [`KeyExpr.intersects`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/KeyExpr.java) (`*`, `**`, `$*`), delivers [`Sample`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/Sample.java) via pull (`poll`/`take`) or push (`forEach`).
   - [`Interest`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Interest.java)-based subscriber discovery: streaming [`discoverTopics`](pure-java/src/main/java/io/mdudel/zenoh/purejava/PureJavaZenohSubscriber.java) (`Mode.CURRENT_FUTURE`) and one-shot synchronous [`listTopicsNow`](pure-java/src/main/java/io/mdudel/zenoh/purejava/PureJavaZenohSubscriber.java) (`Mode.CURRENT`, waits for `DeclareFinal`).
5. **UDP Multicast Scouting**:
   - [`PureJavaZenohScout`](pure-java/src/main/java/io/mdudel/zenoh/purejava/scouting/PureJavaZenohScout.java): Active ([`Scout`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Scout.java) -> [`Hello`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Hello.java)) and passive UDP multicast scouting (`224.0.0.224:7446`).

---

## 2. Capability Comparison Across Upstream Zenoh Projects

| Capability Area | `eclipse-zenoh/zenoh` (Core Rust) | `eclipse-zenoh/zenoh-pico` (Embedded/Client C) | `eclipse-zenoh/zenoh-java` (JNI over Rust) | Local `pure-java` Module |
| :--- | :--- | :--- | :--- | :--- |
| **Deployment Roles** | Router (`zenohd`), Peer, Client | Client, Peer (multicast/unicast) | Client, Peer (via Rust runtime) | **Client only** |
| **Transports** | TCP, TLS, QUIC, UDP, WS/WSS, UnixSocket, Vsock, Serial, SHM | TCP, TLS, UDP, WS, Serial, Bluetooth | All Rust transports via JSON5 config | **TCP, TLS/mTLS, WS/WSS** (UDP scouting only) |
| **Pub / Sub (`PUT`)** | Full (`put`, `Publisher`, `Subscriber`) | Full (`z_put`, `z_declare_publisher`, `z_declare_subscriber`) | Full (`Session.put`, `Publisher`, `Subscriber`) | **Yes** (`PureJavaZenohPublisher`, `PureJavaZenohSubscriber`) |
| **`DELETE` Operation** | Full (`delete`, `SampleKind::Delete`) | Full (`z_delete`, `z_publisher_delete`) | Full (`Session.delete`, `Publisher.delete`) | **Missing** (inbound non-`PUT` dropped) |
| **KeyExpr ID Mapping (`DeclareKeyExpr`)** | Full (numeric `scope` + optional `suffix`) | Full (`z_declare_keyexpr` / inbound table) | Full (`Session.declareKeyExpr`) | **Missing** (inbound `keyScope` with `suffix=null` dropped!) |
| **Multi-Message `Frame` Batching** | Full (packs & unpacks multiple NetMsgs per `Frame`) | Full (packs & unpacks multiple NetMsgs per `Frame`) | Full (via Rust) | **Missing** (decodes only 1st NetMsg per `Frame`!) |
| **Large Payload `Fragment` (`0x06`)** | Full (splits & reassembles >64 KB payloads) | Full (splits & reassembles >64 KB payloads) | Full (via Rust) | **Missing** (hard limit of 65,535 B per frame) |
| **Query / Queryable (`get` / RPC)** | Full (`get`, `Queryable`, `Selector`, `Reply`, `Consolidation`) | Full (`z_get`, `z_declare_queryable`, `z_query_reply`) | Full (`Session.get`, `declareQueryable`, `Reply`) | **Missing** |
| **Liveliness Tokens** | Full (`DeclareToken`, Liveliness Sub & Query) | Full (`z_liveliness_declare_token`, sub, get) | Full (`Session.liveliness()`) | **Missing** |
| **QoS (`Priority`, `CongestionControl`, `Express`)** | Full (8 priority queues, Drop/Block, Express) | Full (QoS extension on `Frame`/`Push`) | Full (`Priority`, `CongestionControl`, `Reliability`) | **Missing in this repo** (implemented in `AsterixIngest` copy) |
| **Attachments & Timestamps** | Full (`Attachment` ZBuf, HLC `Timestamp`, `SourceInfo`) | Full (`z_put_options_t.attachment`, `timestamp`) | Full (`ZBytes` attachment, `Timestamp`) | **Partial** (`Timestamp` decoded on `Put`, not attached on publish; no `Attachment` API) |
| **Auto-Reconnect & Session Recovery** | Full (automatic transport re-establishment & state sync) | Full (`zp_start_read_task` / auto-reconnect helpers) | Full (via Rust transport manager) | **Missing** (session permanently `CLOSED` on disconnect) |
| **Publisher / Subscriber Matching (`matching_listener`)** | Full (`Publisher::matching_listener` via `Interest`) | Full (`z_publisher_declare_background_matching_listener`) | Full (`Publisher.matchingListener` in newer versions) | **Partial** (`Interest` used for topic discovery, not bound to `Publisher`) |
| **Shared Memory (`ZShm`), Router Mesh, Storage Plugins** | Full | None | `ZShm` supported; Router config via JSON5 | **Out of Scope** (pure-Java client) |

---

## 3. Prioritized Missing Capabilities & Implementation Plan (`pure-java`)

Because `zenoh-pico` is the canonical reference for a **lightweight, non-router Zenoh client**, the target capability set for `pure-java` matches `zenoh-pico` + `zenoh-java` client features while keeping **zero JNI / zero external dependencies**.

### Tier 1: Critical Wire-Interoperability & Stability Gaps (Highest Priority)
*Without these, `pure-java` can silently drop messages or fail when talking to real `zenohd`, `zenoh-pico`, or `zenoh` peers under realistic traffic.*

#### 1. Inbound Multi-Message `Frame` Unbatching
- **What is missing**: Per the Zenoh wire protocol (`commons/zenoh-protocol/src/transport/frame.rs`), a single transport `Frame` (`0x05`) carries **one or more** back-to-back self-delimiting network messages (`Push`, `Declare`, `Request`, `Response`, etc.). However, [`ZenohSession.handleInboundFrame`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/ZenohSession.java) currently inspects only `payload[0]` and calls [`Push.decode(byte[])`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Push.java) or [`Declare.decode(byte[])`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Declare.java), leaving any 2nd+ network messages in the `Frame` unread and silently discarded.
- **Necessity**: **Critical (P0)** — fixes silent message loss under load and incomplete `Interest` topic lists when `zenohd` batches multiple `Push` or `Declare` messages into a single `Frame`.
- **Difficulty**: **Low** (~1–2 hours).
- **How to implement**:
  1. Overload `Push.decode(RBuf)`, `Put.decode(RBuf)`, and `Declare.decode(RBuf)` to read directly from a shared [`RBuf`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/RBuf.java) cursor instead of consuming `r.bytes(r.remaining())`.
  2. In `ZenohSession.handleInboundFrame(Frame)`, wrap `frame.payload()` in an `RBuf` and loop `while (r.remaining() > 0)` to dispatch each network message sequentially.

#### 2. Inbound & Outbound Numeric KeyExpr Mapping (`DeclareKeyExpr` / `UndeclareKeyExpr`)
- **What is missing**: In `zenoh`, `zenoh-pico`, and `zenoh-java`, any publisher created via `declarePublisher("a/b/c")` or `declareKeyExpr("a/b/c")` registers a numeric `expr_id` (`DeclareKeyExpr`, declaration ID `0x00`) and then sends `Push` frames with `keyScope = expr_id` and `keySuffix = null` (or a relative suffix). Currently, [`ZenohSession.deliverPush`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/ZenohSession.java) checks `if (key == null || key.isEmpty()) return;`, **silently dropping every message from any publisher that uses declared key expressions**.
- **Necessity**: **Critical (P0)** — required to receive messages from standard `zenoh-java`, Rust `zenoh`, and `zenoh-pico` publishers that use `declare_publisher` / `declare_keyexpr`. Also reduces outbound wire overhead when publishing repeatedly to the same topic.
- **Difficulty**: **Low–Medium** (~3–4 hours).
- **How to implement**:
  1. Add `DeclareKeyExpr` (`id = 0x00`, `int exprId`, `String keyExpr`) and `UndeclareKeyExpr` (`id = 0x01`, `int exprId`) wire records in `io.mdudel.zenoh.purejava.wire.messages`, and wire them into `Declare.java`.
  2. Maintain `ConcurrentHashMap<Integer, String> remoteKeyExprs` (for `M=1` sender-declared mappings) and `localKeyExprs` (for `M=0` receiver-declared mappings) in `ZenohSession`.
  3. Update `ZenohSession.deliverPush` to resolve `(keyScope, keySuffix, senderMapping)`:
     - `keyScope == 0`: `keySuffix` is the full key expression.
     - `keyScope != 0`: look up `prefix` in `remoteKeyExprs` (if `M=1`) or `localKeyExprs` (if `M=0`); if `keySuffix == null`, full key is `prefix`, otherwise `prefix + keySuffix`.

#### 3. Backporting Already-Built `AsterixIngest` Fixes (`PemLoader` EC Private Keys, QoS Extensions, `TopicRegistry`)
- **What is missing in this repo**:
  1. **Bug in [`PemLoader.EC_OID`](pure-java/src/main/java/io/mdudel/zenoh/purejava/transport/PemLoader.java)**: Currently `EC_OID` is a malformed 11-byte DER prefix (`0x30, 0x0b, ...`) that omits the curve OID from SEC 1 `-----BEGIN EC PRIVATE KEY-----` files, causing `KeyFactory.getInstance("EC")` to throw `InvalidKeySpecException`.
  2. **QoS (`Priority`, `CongestionControl`, `express`)**: Outbound `Frame` and `Push` hardcode empty extensions.
  3. **`TopicRegistry` / `TopicInfo` / `TopicSource`**: Combining active `Interest` (`SUBSCRIBERS` + `KEY_EXPRS`) with passive live-traffic observation so both subscribers and active publishers appear in topic listings.
- **Necessity**: **High (P0/P1)** — already built and tested in the vendored copy in `AsterixIngest`, just needs backporting here so this repository stays canonical.
- **Difficulty**: **Very Low** (~1 hour).

#### 4. Transport Fragmentation (`Fragment` Message `0x06`)
- **What is missing**: [`StreamFramer`](pure-java/src/main/java/io/mdudel/zenoh/purejava/transport/StreamFramer.java) enforces a 16-bit length prefix (`MAX_BATCH_BYTES = 65_535`). Any payload larger than ~64 KB throws `IOException` on send, and inbound `Fragment` (`0x06`) transport messages from a router are currently ignored in [`ZenohSession.dispatchInbound`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/ZenohSession.java). Both `zenoh` and `zenoh-pico` split serialized network messages >64 KB across multiple `Fragment` transport messages (`id=0x06`, `R` reliable flag, `M` more-fragments flag, `sn` varint) and reassemble them before network-message decoding.
- **Necessity**: **High (P1)** — essential whenever publishing or subscribing to payloads >64 KB (large JSON batches, radar/sensor frames, binary blobs).
- **Difficulty**: **Medium** (~4–6 hours).
- **How to implement**:
  1. Create `Fragment.java` (`ID = 0x06`, `FLAG_R = 0x20`, `FLAG_M = 0x40`, `FLAG_Z = 0x80`, `long sn`, `List<Extension> extensions`, `byte[] payload`).
  2. On outbound send in `ZenohSession`: if an encoded network message exceeds the negotiated max batch size (`65_535` minus header overhead), slice its bytes into chunks and send each chunk as a `Fragment` with its own sequential `sn` (`more = true` on all but the final chunk).
  3. On inbound receive in `ZenohSession`: maintain a `ByteArrayOutputStream` reassembly buffer and expected next `sn` per channel (reliable / best-effort). Append `Fragment` payloads while `more == true`; if a sequence gap occurs, discard the partial buffer; when `more == false`, pass the reassembled byte array to the network-message dispatcher.

#### 5. Automatic Reconnect & State Re-declaration (`Subscription` / `Interest` / `KeyExpr`)
- **What is missing**: In [`ZenohSession`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/ZenohSession.java), if the TCP/TLS socket drops or the router lease expires, the session transitions permanently to `State.CLOSED` and all active [`Subscription`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/Subscription.java) queues stop receiving forever. Both `zenoh` and `zenoh-pico` support automatic transport re-establishment and re-sending active declarations (`DeclareSubscriber`, `DeclareKeyExpr`, `Interest`) to the router.
- **Necessity**: **High (P1)** — critical for long-running services where `zenohd` restarts or transient network drops occur.
- **Difficulty**: **Medium** (~4–6 hours).
- **How to implement**:
  1. Add an opt-in `ReconnectConfig` (initial delay, max delay, backoff multiplier, max attempts) to `ZenohSession` / `PureJavaZenohPublisher.Builder` / `PureJavaZenohSubscriber.Builder`.
  2. When an unexpected I/O failure or lease timeout occurs (as opposed to an explicit user `close()`), transition to `State.RECONNECTING` and launch a scheduled reconnect task.
  3. Re-establish the transport, perform the 4-message `Init`/`Open` handshake, reset sequence numbers and `remoteKeyExprs`, re-send `Declare` messages for all active `Subscription`s, `Interest`s, and `localKeyExprs`, and resume the reader and keep-alive loops without invalidating user `Subscription` instances.

---

### Tier 2: Core Zenoh Functional Primitives (High Value for Full Client Parity)
*These bring `pure-java` to functional feature parity with `zenoh-pico` and `zenoh-java`.*

#### 6. Query / Queryable (`get` & `declareQueryable` — Request / Response / Admin Space)
- **What is missing**: Zenoh's second major pillar alongside Pub/Sub is **Query/Queryable** (distributed RPC and fetching state from Zenoh storages or router admin space).
  - **Bonus capability unlocked**: Zenoh routers (`zenohd`) expose an **Admin Space** under `@/**` (e.g., `@/*/router/**`, `@/*/session/**`) via Queryables. Implementing `session.get(selector)` allows `pure-java` to query `zenohd` for connected sessions, plugins, router metrics, and **declared publishers** even when those publishers are idle.
- **Necessity**: **High (P1/P2)** — required for request/reply patterns, querying Zenoh storage backends, and inspecting router admin space (`@/**`).
- **Difficulty**: **Medium–High** (~1–2 days).
- **How to implement**:
  1. Implement network messages `Request` (`0x1C` / `0x1D` per wire v0.9), `Response` (`0x1B`), and `ResponseFinal` (`0x1A`), plus body codecs `Query` (`0x03`, parameters string + optional `Consolidation` / `Value` / `Attachment` extensions) and `Reply` (`0x04`, replier ID + `Put`/`Del` body) and declarations `DeclareQueryable` (`0x04`) / `UndeclareQueryable` (`0x05`).
  2. Add `session.get(String selector, Duration timeout)` returning `List<Reply>` (or streaming `BlockingQueue<Reply>` until `ResponseFinal`).
  3. Add `session.declareQueryable(String keyExpr, Consumer<Query> handler)` so a Java service can answer queries.

#### 7. `Delete` Operation (`Del` PushBody `0x02`) & `Sample.Kind` (`PUT` vs `DELETE`)
- **What is missing**: Zenoh Pub/Sub has two verbs on a key expression: `PUT` (`0x01`) and `DELETE` (`0x02`). Currently [`ZenohSession.deliverPush`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/ZenohSession.java) checks `if (bodyId != Put.ID) return;` and drops `DELETE` messages. Neither [`PureJavaZenohPublisher`](pure-java/src/main/java/io/mdudel/zenoh/purejava/PureJavaZenohPublisher.java) nor [`Sample`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/Sample.java) supports `DELETE`.
- **Necessity**: **Medium–High (P2)** — standard part of Zenoh Pub/Sub lifecycle (signaling track eviction, sensor offline, or key removal in Zenoh storages).
- **Difficulty**: **Low** (~2 hours).
- **How to implement**:
  1. Add `Del.java` (`ID = 0x02`, `Timestamp timestamp`, `List<Extension> extensions`).
  2. Add `Sample.Kind { PUT, DELETE }` to [`Sample`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/Sample.java) (defaulting to `PUT` for backward compatibility).
  3. Add `delete(String subKey)` to [`PureJavaZenohPublisher`](pure-java/src/main/java/io/mdudel/zenoh/purejava/PureJavaZenohPublisher.java) and `ZenohSession`.

#### 8. User `Attachment` & Automatic HLC `Timestamp` on Outbound `Put` + Inbound `Sample`
- **What is missing**:
  - [`Timestamp.now(ZenohId)`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/Timestamp.java) and [`Put`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Put.java)'s `timestamp` / `extensions` fields already exist on the wire layer, but [`PureJavaZenohPublisher`](pure-java/src/main/java/io/mdudel/zenoh/purejava/PureJavaZenohPublisher.java) always passes `null` for `timestamp` and `List.of()` for `extensions`.
  - In `zenoh`, `zenoh-pico`, and `zenoh-java`, callers can attach arbitrary metadata (`Attachment`, wire extension `id = 0x03` ZBuf on `Put`/`Del`/`Query`) and opt into automatic HLC timestamping (`Timestamp`), and subscribers can read `sample.attachment()` on [`Sample`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/Sample.java).
- **Necessity**: **Medium (P2)** — commonly used for passing trace IDs, headers, or source metadata alongside payloads without modifying the payload body.
- **Difficulty**: **Low** (~2–3 hours).

#### 9. Liveliness Primitives (`DeclareToken` / `UndeclareToken` & Liveliness Subscriber/Query)
- **What is missing**: `zenoh`, `zenoh-pico`, and `zenoh-java` provide a `Liveliness` API (`DeclareToken` `0x06`, `UndeclareToken` `0x07`, plus `Interest` with `FLAG_TOKENS = 0x08`). When a client declares a liveliness token (e.g. `radar/node-1`), routers automatically notify liveliness subscribers when that token is declared **and** automatically emit `UndeclareToken` if the client's session disconnects unexpectedly.
- **Necessity**: **Medium (P2)** — standard Zenoh mechanism for presence/health monitoring across nodes.
- **Difficulty**: **Low–Medium** (~3–4 hours).

---

### Tier 3: Ergonomic & Secondary Transport Features (Lower Priority)

#### 10. Scout-to-Connect Auto-Discovery & Multi-Endpoint Failover
- **What is missing**: In `zenoh-java` and `zenoh-pico`, if no explicit `connect` endpoint is configured, the client automatically broadcasts a UDP multicast [`Scout`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Scout.java) (`WhatAmI.ROUTER | PEER`), picks the first reachable TCP/TLS/WS locator from the [`Hello`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Hello.java) reply, and connects to it—and supports a fallback list of `connectEndpoints` (`["tls/router1:7447", "tls/router2:7447"]`).
- **Necessity**: **Medium–Low (P3)** — convenient for zero-config LAN discovery and multi-router HA failover, and [`PureJavaZenohScout`](pure-java/src/main/java/io/mdudel/zenoh/purejava/scouting/PureJavaZenohScout.java) already exists.
- **Difficulty**: **Low** (~2 hours).

#### 11. Publisher Matching Listener (`Publisher.matchingListener`)
- **What is missing**: `zenoh`, `zenoh-pico`, and `zenoh-java` allow a publisher to know whether at least one subscriber is currently matching its key expression (so a producer can pause expensive work when nobody is listening). Under the hood, this is an [`Interest`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Interest.java) (`Mode.CURRENT_FUTURE`, `FLAG_SUBSCRIBERS | FLAG_AGGREGATE`) scoped to the publisher's key expression.
- **Necessity**: **Low–Medium (P3)**.
- **Difficulty**: **Low** (~2 hours, since [`Interest`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Interest.java) is already implemented).

#### 12. Unicast UDP Session Transport (`udp/host:port`)
- **What is missing**: Running full Zenoh client sessions over unicast UDP (`DatagramChannel`) instead of TCP/TLS/WS.
- **Necessity**: **Low (P3)** — TCP, TLS, and WS cover almost all enterprise/accredited deployments.
- **Difficulty**: **Medium** (~1 day).

#### 13. QUIC Transport (`quic/host:port`), Peer/Router Mesh Mode, and Shared Memory (`ZShm`)
- **What is missing**:
  - `quic/host:port`: JDK 17 has no built-in QUIC stack in the standard library; supporting QUIC without JNI requires a third-party pure-Java library (e.g. `kwik`) in an optional submodule.
  - Peer-to-peer linkstate/gossip routing, router brokering, and POSIX shared memory (`ZShm`).
- **Necessity**: **Out of Scope / P4** — explicitly non-goals for a zero-dependency pure-Java client.
- **Difficulty**: **High to Very High**.

---

## 4. Summary Priority Matrix

| Rank | Capability | Priority | Necessity / Impact | Difficulty (Pure Java) | Est. Effort |
| :---: | :--- | :---: | :--- | :---: | :---: |
| **1** | **Multi-message `Frame` unbatching** | **P0** | Prevents silent loss of 2nd+ messages in batched `Frame`s from `zenohd` | **Low** | ~1–2 hrs |
| **2** | **Numeric KeyExpr mapping (`DeclareKeyExpr` / `UndeclareKeyExpr`)** | **P0** | Required to receive data from `zenoh`/`zenoh-pico`/`zenoh-java` `declare_publisher` | **Low–Med** | ~3–4 hrs |
| **3** | **Backport `AsterixIngest` additions (`PemLoader` EC fix, QoS, `TopicRegistry`)** | **P0** | Fixes `EC PRIVATE KEY` PEM loading bug; adds QoS & unified topic registry | **Very Low** | ~1 hr |
| **4** | **Transport Fragmentation (`Fragment` `0x06` send & receive)** | **P1** | Unlocks payloads >64 KB (`StreamFramer` 65,535-byte ceiling) | **Medium** | ~4–6 hrs |
| **5** | **Auto-Reconnect & Subscription/Interest Re-declaration** | **P1** | Keeps long-lived clients alive across `zenohd` restarts / network drops | **Medium** | ~4–6 hrs |
| **6** | **Query / Queryable (`get` & `declareQueryable` + `@/**` Admin Space)** | **P1/P2** | Core Zenoh RPC verb + allows querying `zenohd` admin space for publishers | **Med–High** | ~1–2 days |
| **7** | **`Delete` operation (`Del` `0x02`) + `Sample.Kind.DELETE`** | **P2** | Completes Zenoh Pub/Sub verb pair (`PUT` + `DELETE`) | **Low** | ~2 hrs |
| **8** | **Attachments (`ext_attachment`) & Outbound HLC `Timestamp`** | **P2** | Metadata headers & source timestamps on published/received samples | **Low** | ~2–3 hrs |
| **9** | **Liveliness Tokens (`DeclareToken` / `UndeclareToken`)** | **P2** | Presence/health detection across Zenoh nodes | **Low–Med** | ~3–4 hrs |
| **10** | **Multi-Endpoint Failover & Scout Auto-Connect** | **P3** | Zero-config LAN discovery & multi-router HA failover | **Low** | ~2 hrs |
| **11** | **Publisher Matching Listener (`matchingListener`)** | **P3** | Lets publisher know when >=1 subscriber is listening | **Low** | ~2 hrs |
