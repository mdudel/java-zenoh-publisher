# Zenoh Capability Assessment & Pure-Java Roadmap

Assessment of current capabilities in `java-zenoh-publisher` (specifically the zero-JNI `pure-java` module) compared against upstream [eclipse-zenoh/zenoh](https://github.com/eclipse-zenoh/zenoh), [eclipse-zenoh/zenoh-pico](https://github.com/eclipse-zenoh/zenoh-pico), and [eclipse-zenoh/zenoh-java](https://github.com/eclipse-zenoh/zenoh-java), along with a prioritized implementation plan for the remaining missing features using **pure Java (JDK 17 stdlib only, zero JNI/Rust dependencies)**.

---

## 1. Current Capabilities in `java-zenoh-publisher`

This repository contains two distinct implementations:

### A. Root Module (`io.mdudel:java-zenoh-publisher` — JNI wrapper)
- Wraps official `io.zenoh:zenoh-java:1.9.0` (JNI over Rust `zenoh`, `x86_64` native libraries only).
- [`ZenohClient`](src/main/java/io/mdudel/zenoh/ZenohClient.java): Publisher-only wrapper with endpoint connect (`tcp/`, `udp/`, `tls/`, `quic/`, `ws/`, `wss/`, or default multicast peer mode), `org` prefix resolution, JSON5 TLS/mTLS config generation, lazy per-subkey `Publisher` cache (`declarePublisher`), and basic counters (`sentCount`, `lastSendMs`, `lastError`).
- [`ZenohPublisherApp`](src/main/java/io/mdudel/zenoh/ZenohPublisherApp.java): CLI publisher entrypoint.

### B. Pure-Java Module (`io.mdudel:java-zenoh-publisher-pure` in [`pure-java/`](pure-java/README.md))
Clean-room implementation of **Zenoh 1.x wire protocol v0.9 (`0x09`)** in **JDK 17 stdlib only** (zero JNI, zero Rust, zero third-party runtime dependencies):
1. **Transports (`io.mdudel.zenoh.purejava.transport`)**:
   - **TCP**: [`TcpTransport`](pure-java/src/main/java/io/mdudel/zenoh/purejava/transport/TcpTransport.java) (`tcp/host:port`) with 16-bit little-endian [`StreamFramer`](pure-java/src/main/java/io/mdudel/zenoh/purejava/transport/StreamFramer.java) (max 65,535-byte transport batch).
   - **TLS / mTLS**: [`TlsTransport`](pure-java/src/main/java/io/mdudel/zenoh/purejava/transport/TlsTransport.java) (`tls/host:port`), [`TlsConfig`](pure-java/src/main/java/io/mdudel/zenoh/purejava/transport/TlsConfig.java), and [`PemLoader`](pure-java/src/main/java/io/mdudel/zenoh/purejava/transport/PemLoader.java) (TLS 1.2/1.3, server CA trust, client mTLS, PKCS#8 PEM, PKCS#1 RSA PEM, **RFC 5915 SEC 1 `EC PRIVATE KEY` PEM**, `RSA PUBLIC KEY` / `PUBLIC KEY` PEM, PKCS#12 `.p12`/`.pfx`, optional hostname verification, and TLS peer certificate pinning).
   - **WebSocket**: [`WsTransport`](pure-java/src/main/java/io/mdudel/zenoh/purejava/transport/WsTransport.java) (`ws/host:port` and `wss/host:port` via `java.net.http.WebSocket`, one transport message per binary WS frame).
2. **Session, Handshake, Authentication & Auto-Reconnect (`io.mdudel.zenoh.purejava.session`)**:
   - [`ZenohSession`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/ZenohSession.java): 4-message client handshake ([`Init.InitSyn`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Init.java) -> [`Init.InitAck`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Init.java) -> [`Open.OpenSyn`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Open.java) -> [`Open.OpenAck`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Open.java)), monotonic sequence numbers, periodic [`KeepAlive`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/KeepAlive.java) (`lease / 4`), lease-expiry watchdog, clean [`Close`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Close.java) teardown, and single-reader background thread.
   - **Zenoh 1.9 RSA Transport Authentication**: [`SessionAuthenticator`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/SessionAuthenticator.java) and [`ZenohRsaAuthenticator`](pure-java/src/main/java/io/mdudel/zenoh/purejava/connection/auth/ZenohRsaAuthenticator.java) implement Zenoh 1.9's `transport/auth/pubkey.rs` challenge-response protocol (`ext_auth` ID `0x04`, RSA-OAEP-SHA256 nonce encryption/decryption).
   - **Inbound Multi-Message `Frame` Unbatching**: [`ZenohSession.handleInboundFrame`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/ZenohSession.java), [`Push.decode(RBuf)`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Push.java), [`Put.decode(RBuf)`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Put.java), and [`Declare.decode(RBuf)`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Declare.java) iterate through all back-to-back network messages packed into a single transport [`Frame`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Frame.java).
   - **Inbound KeyExpr Aliasing (`DeclareKeyExpr` / `UndeclareKeyExpr`)**: [`DeclareKeyExpr`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/DeclareKeyExpr.java) (`0x00`) and [`UndeclareKeyExpr`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/UndeclareKeyExpr.java) (`0x01`) maintain a session-scoped alias table (`keyExprByScope`) and resolve numeric `keyScope` + optional `keySuffix` on inbound [`Push`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Push.java) messages.
   - **Automatic Reconnect & State Re-declaration**: Opt-in `autoReconnect(true)` with configurable exponential backoff (`reconnectInitialDelayMs`, `reconnectMaxDelayMs`). On unexpected transport disconnect, server `Close`, or lease expiry, [`ZenohSession`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/ZenohSession.java) transitions to [`SessionState.RECONNECTING`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/SessionState.java), re-establishes the underlying [`Transport.reconnect()`](pure-java/src/main/java/io/mdudel/zenoh/purejava/transport/Transport.java), completes a fresh `Init`/`Open` handshake, and automatically re-sends [`DeclareSubscriber`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/DeclareSubscriber.java) and [`Interest`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Interest.java) (`Mode.CURRENT_FUTURE`) frames for all active [`Subscription`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/Subscription.java)s and [`InterestListener`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/InterestListener.java)s.
3. **Publishing & QoS Extensions**:
   - [`PureJavaZenohPublisher`](pure-java/src/main/java/io/mdudel/zenoh/purejava/PureJavaZenohPublisher.java): Sends [`Frame`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Frame.java) -> [`Push`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Push.java) -> [`Put`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Put.java) with [`Encoding`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/Encoding.java).
   - **QoS Support**: [`Qos`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/Qos.java), [`Priority`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/Priority.java), [`CongestionControl`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/CongestionControl.java), and [`PushExts`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/PushExts.java) encode QoS (`Priority`, `CongestionControl`, `express`), `Timestamp`, and `NodeId` extensions on both network [`Push`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Push.java) and transport [`Frame`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Frame.java) headers.
4. **Subscribing, Unified Pub/Sub Connection & Multi-Source Topic Discovery**:
   - [`PureJavaZenohSubscriber`](pure-java/src/main/java/io/mdudel/zenoh/purejava/PureJavaZenohSubscriber.java) & [`Subscription`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/Subscription.java): Sends [`Declare`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Declare.java) + [`DeclareSubscriber`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/DeclareSubscriber.java) / [`UndeclareSubscriber`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/UndeclareSubscriber.java), matches inbound [`Push`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Push.java) via [`KeyExpr.intersects`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/KeyExpr.java) (`*`, `**`, `$*`), delivers [`Sample`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/Sample.java) via pull (`poll`/`take`) or push (`forEach`).
   - **Unified Pub/Sub Connection & Local Loopback**: [`ZenohConnection`](pure-java/src/main/java/io/mdudel/zenoh/purejava/connection/ZenohConnection.java) multiplexes publishing, subscribing, and topic discovery over a single shared [`ZenohSession`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/ZenohSession.java) with in-process local routing (`localRouting=true`) so co-located subscribers receive local publishes directly (`zenohd` never echoes a client's `Push` back up the same link).
   - **Multi-Source Topic Discovery**: [`TopicRegistry`](pure-java/src/main/java/io/mdudel/zenoh/purejava/TopicRegistry.java), [`TopicInfo`](pure-java/src/main/java/io/mdudel/zenoh/purejava/TopicInfo.java), and [`TopicSource`](pure-java/src/main/java/io/mdudel/zenoh/purejava/TopicSource.java) merge declarations across all four [`Interest`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Interest.java) entity types ([`DeclareSubscriber`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/DeclareSubscriber.java), [`DeclareKeyExpr`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/DeclareKeyExpr.java), [`DeclareQueryable`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/DeclareQueryable.java), [`DeclareToken`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/DeclareToken.java)) alongside passive `PUT` traffic observation (`startRecordingTopics`).
5. **UDP Multicast Scouting**:
   - [`PureJavaZenohScout`](pure-java/src/main/java/io/mdudel/zenoh/purejava/scouting/PureJavaZenohScout.java): Active ([`Scout`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Scout.java) -> [`Hello`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Hello.java)) and passive UDP multicast scouting (`224.0.0.224:7446`).

---

## 2. Capability Comparison Across Upstream Zenoh Projects

| Capability Area | `eclipse-zenoh/zenoh` (Core Rust) | `eclipse-zenoh/zenoh-pico` (Embedded/Client C) | `eclipse-zenoh/zenoh-java` (JNI over Rust) | Local `pure-java` Module |
| :--- | :--- | :--- | :--- | :--- |
| **Deployment Roles** | Router (`zenohd`), Peer, Client | Client, Peer (multicast/unicast) | Client, Peer (via Rust runtime) | **Client only** |
| **Transports** | TCP, TLS, QUIC, UDP, WS/WSS, UnixSocket, Vsock, Serial, SHM | TCP, TLS, UDP, WS, Serial, Bluetooth | All Rust transports via JSON5 config | **TCP, TLS/mTLS, WS/WSS** (UDP scouting only) |
| **Pub / Sub (`PUT`)** | Full (`put`, `Publisher`, `Subscriber`) | Full (`z_put`, `z_declare_publisher`, `z_declare_subscriber`) | Full (`Session.put`, `Publisher`, `Subscriber`) | **Yes** (`PureJavaZenohPublisher`, `PureJavaZenohSubscriber`, `ZenohConnection`) |
| **Multi-Message `Frame` Batching** | Full (packs & unpacks multiple NetMsgs per `Frame`) | Full (packs & unpacks multiple NetMsgs per `Frame`) | Full (via Rust) | **Yes (Inbound Unbatching)** |
| **KeyExpr ID Mapping (`DeclareKeyExpr`)** | Full (numeric `scope` + optional `suffix`) | Full (`z_declare_keyexpr` / inbound table) | Full (`Session.declareKeyExpr`) | **Yes (Inbound Resolution + Discovery)** |
| **QoS (`Priority`, `CongestionControl`, `Express`)** | Full (8 priority queues, Drop/Block, Express) | Full (QoS extension on `Frame`/`Push`) | Full (`Priority`, `CongestionControl`, `Reliability`) | **Yes** (`Qos`, `Priority`, `CongestionControl`, `PushExts`) |
| **Auto-Reconnect & Session Recovery** | Full (automatic transport re-establishment & state sync) | Full (`zp_start_read_task` / auto-reconnect helpers) | Full (via Rust transport manager) | **Yes** (`autoReconnect`, re-declares `Subscription`s & `Interest`s) |
| **Transport RSA Authentication (`ext_auth`)** | Full (`transport/auth/pubkey.rs`) | Partial | Via JSON5 config | **Yes** (`ZenohRsaAuthenticator` / `SessionAuthenticator`) |
| **`DELETE` Operation** | Full (`delete`, `SampleKind::Delete`) | Full (`z_delete`, `z_publisher_delete`) | Full (`Session.delete`, `Publisher.delete`) | **Missing** (inbound non-`PUT` dropped) |
| **Large Payload `Fragment` (`0x06`)** | Full (splits & reassembles >64 KB payloads) | Full (splits & reassembles >64 KB payloads) | Full (via Rust) | **Missing** (hard limit of 65,535 B per frame) |
| **Query / Queryable (`get` / RPC)** | Full (`get`, `Queryable`, `Selector`, `Reply`, `Consolidation`) | Full (`z_get`, `z_declare_queryable`, `z_query_reply`) | Full (`Session.get`, `declareQueryable`, `Reply`) | **Partial** (`DeclareQueryable`/`UndeclareQueryable` wire codecs implemented; `get`/reply missing) |
| **Liveliness Tokens** | Full (`DeclareToken`, Liveliness Sub & Query) | Full (`z_liveliness_declare_token`, sub, get) | Full (`Session.liveliness()`) | **Partial** (`DeclareToken`/`UndeclareToken` wire codecs + discovery implemented; client token declaration API missing) |
| **Attachments & Timestamps** | Full (`Attachment` ZBuf, HLC `Timestamp`, `SourceInfo`) | Full (`z_put_options_t.attachment`, `timestamp`) | Full (`ZBytes` attachment, `Timestamp`) | **Partial** (`Timestamp` decoded on `Put` and supported on `PushExts`; no user `Attachment` API) |
| **Publisher / Subscriber Matching (`matching_listener`)** | Full (`Publisher::matching_listener` via `Interest`) | Full (`z_publisher_declare_background_matching_listener`) | Full (`Publisher.matchingListener` in newer versions) | **Partial** (`Interest` used for topic discovery, not bound to `Publisher`) |
| **Shared Memory (`ZShm`), Router Mesh, Storage Plugins** | Full | None | `ZShm` supported; Router config via JSON5 | **Out of Scope** (pure-Java client) |

---

## 3. Recently Completed Capabilities (`pure-java`)

The following high-priority capabilities have been implemented and verified in `pure-java`:
- **[DONE] Inbound Multi-Message `Frame` Unbatching**: [`ZenohSession.handleInboundFrame`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/ZenohSession.java) loops across `RBuf.remaining() > 0` using cursor-aware [`Push.decode(RBuf)`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Push.java), [`Put.decode(RBuf)`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Put.java), and [`Declare.decode(RBuf)`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Declare.java).
- **[DONE] Numeric KeyExpr Aliasing (`DeclareKeyExpr` / `UndeclareKeyExpr`)**: [`DeclareKeyExpr`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/DeclareKeyExpr.java) and [`UndeclareKeyExpr`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/UndeclareKeyExpr.java) populate `keyExprByScope` in [`ZenohSession`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/ZenohSession.java), resolving `keyScope` + `keySuffix` on inbound `Push` frames.
- **[DONE] Automatic Reconnect & State Re-declaration**: [`ZenohSession`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/ZenohSession.java), [`AbstractTransport.reconnect()`](pure-java/src/main/java/io/mdudel/zenoh/purejava/transport/AbstractTransport.java), [`PureJavaZenohPublisher`](pure-java/src/main/java/io/mdudel/zenoh/purejava/PureJavaZenohPublisher.java), [`PureJavaZenohSubscriber`](pure-java/src/main/java/io/mdudel/zenoh/purejava/PureJavaZenohSubscriber.java), and [`ZenohConnection`](pure-java/src/main/java/io/mdudel/zenoh/purejava/connection/ZenohConnection.java) support automatic reconnect with exponential backoff and seamless re-declaration of all active [`Subscription`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/Subscription.java)s and [`Interest`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Interest.java) listeners.
- **[DONE] Backported `AsterixIngest` Enhancements**:
  - Fixed RFC 5915 SEC 1 `EC PRIVATE KEY` curve OID wrapping in [`PemLoader`](pure-java/src/main/java/io/mdudel/zenoh/purejava/transport/PemLoader.java) (`PemLoaderECTest`).
  - Added [`Qos`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/Qos.java), [`Priority`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/Priority.java), [`CongestionControl`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/CongestionControl.java), and [`PushExts`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/PushExts.java).
  - Added [`TopicRegistry`](pure-java/src/main/java/io/mdudel/zenoh/purejava/TopicRegistry.java), [`TopicInfo`](pure-java/src/main/java/io/mdudel/zenoh/purejava/TopicInfo.java), [`TopicSource`](pure-java/src/main/java/io/mdudel/zenoh/purejava/TopicSource.java), [`DeclareQueryable`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/DeclareQueryable.java), [`UndeclareQueryable`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/UndeclareQueryable.java), [`DeclareToken`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/DeclareToken.java), and [`UndeclareToken`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/UndeclareToken.java).
  - Added [`ZenohConnection`](pure-java/src/main/java/io/mdudel/zenoh/purejava/connection/ZenohConnection.java), [`ZenohRsaAuthenticator`](pure-java/src/main/java/io/mdudel/zenoh/purejava/connection/auth/ZenohRsaAuthenticator.java), and [`SessionAuthenticator`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/SessionAuthenticator.java).

---

## 4. Prioritized Remaining Missing Capabilities & Implementation Plan (`pure-java`)

### Tier 1: Wire Scale & Payload Completeness (Highest Priority)

#### 1. Transport Fragmentation (`Fragment` Message `0x06`)
- **What is missing**: [`StreamFramer`](pure-java/src/main/java/io/mdudel/zenoh/purejava/transport/StreamFramer.java) enforces a 16-bit length prefix (`MAX_BATCH_BYTES = 65_535`). Any payload larger than ~64 KB throws `IOException` on send, and inbound `Fragment` (`0x06`) transport messages from a router are currently ignored in [`ZenohSession.dispatchInbound`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/ZenohSession.java). Both `zenoh` and `zenoh-pico` split serialized network messages >64 KB across multiple `Fragment` transport messages (`id=0x06`, `R` reliable flag, `M` more-fragments flag, `sn` varint) and reassemble them before network-message decoding.
- **Necessity**: **High (P1)** — essential whenever publishing or subscribing to payloads >64 KB (large JSON batches, radar/sensor frames, binary blobs).
- **Difficulty**: **Medium** (~4–6 hours).
- **How to implement**:
  1. Create `Fragment.java` (`ID = 0x06`, `FLAG_R = 0x20`, `FLAG_M = 0x40`, `FLAG_Z = 0x80`, `long sn`, `List<Extension> extensions`, `byte[] payload`).
  2. On outbound send in [`ZenohSession`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/ZenohSession.java): if an encoded network message exceeds the negotiated max batch size (`65_535` minus header overhead), slice its bytes into chunks and send each chunk as a `Fragment` with its own sequential `sn` (`more = true` on all but the final chunk).
  3. On inbound receive in [`ZenohSession`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/ZenohSession.java): maintain a `ByteArrayOutputStream` reassembly buffer and expected next `sn` per channel (reliable / best-effort). Append `Fragment` payloads while `more == true`; if a sequence gap occurs, discard the partial buffer; when `more == false`, pass the reassembled byte array to `handleInboundPayload`.

---

### Tier 2: Core Zenoh Functional Primitives (Full Client Parity)

#### 2. Query / Queryable (`get` & `declareQueryable` — Request / Response / Admin Space)
- **What is missing**: Zenoh's second major pillar alongside Pub/Sub is **Query/Queryable** (distributed RPC and fetching state from Zenoh storages or router admin space). Note that [`DeclareQueryable`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/DeclareQueryable.java) and [`UndeclareQueryable`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/UndeclareQueryable.java) wire codecs are already implemented, leaving the request/response message exchange (`Request`, `Response`, `ResponseFinal`).
  - **Bonus capability unlocked**: Zenoh routers (`zenohd`) expose an **Admin Space** under `@/**` (e.g., `@/*/router/**`, `@/*/session/**`) via Queryables. Implementing `session.get(selector)` allows `pure-java` to query `zenohd` for connected sessions, plugins, router metrics, and **declared publishers** even when those publishers are idle.
- **Necessity**: **High (P1/P2)** — required for request/reply patterns, querying Zenoh storage backends, and inspecting router admin space (`@/**`).
- **Difficulty**: **Medium–High** (~1–2 days).
- **How to implement**:
  1. Implement network messages `Request` (`0x1D` / `0x1C`), `Response` (`0x1B`), and `ResponseFinal` (`0x1A`), plus body codecs `Query` (`0x03`, parameters string + optional `Consolidation` / `Value` / `Attachment` extensions) and `Reply` (`0x04`, replier ID + `Put`/`Del` body).
  2. Add `session.get(String selector, Duration timeout)` returning `List<Reply>` (or streaming `BlockingQueue<Reply>` until `ResponseFinal`).
  3. Add `session.declareQueryable(String keyExpr, Consumer<Query> handler)` so a Java service can answer queries.

#### 3. `Delete` Operation (`Del` PushBody `0x02`) & `Sample.Kind` (`PUT` vs `DELETE`)
- **What is missing**: Zenoh Pub/Sub has two verbs on a key expression: `PUT` (`0x01`) and `DELETE` (`0x02`). Currently [`ZenohSession.deliverPush`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/ZenohSession.java) checks `if (bodyId != Put.ID) return;` and drops `DELETE` messages. Neither [`PureJavaZenohPublisher`](pure-java/src/main/java/io/mdudel/zenoh/purejava/PureJavaZenohPublisher.java) nor [`Sample`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/Sample.java) supports `DELETE`.
- **Necessity**: **Medium–High (P2)** — standard part of Zenoh Pub/Sub lifecycle (signaling track eviction, sensor offline, or key removal in Zenoh storages).
- **Difficulty**: **Low** (~2 hours).
- **How to implement**:
  1. Add `Del.java` (`ID = 0x02`, `Timestamp timestamp`, `List<Extension> extensions`).
  2. Add `Sample.Kind { PUT, DELETE }` to [`Sample`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/Sample.java) (defaulting to `PUT` for backward compatibility).
  3. Add `delete(String subKey)` to [`PureJavaZenohPublisher`](pure-java/src/main/java/io/mdudel/zenoh/purejava/PureJavaZenohPublisher.java), [`ZenohConnection`](pure-java/src/main/java/io/mdudel/zenoh/purejava/connection/ZenohConnection.java), and [`ZenohSession`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/ZenohSession.java).

#### 4. Outbound Liveliness Token Declaration (`declareLivelinessToken`)
- **What is missing**: [`DeclareToken`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/DeclareToken.java) (`0x06`) and [`UndeclareToken`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/UndeclareToken.java) (`0x07`) wire codecs and inbound `Interest` discovery (`FLAG_TOKENS`) are already implemented, but `ZenohSession` does not yet expose a client API (`declareLivelinessToken(String keyExpr)`) to register a local liveliness token and re-declare it across reconnects.
- **Necessity**: **Medium (P2)** — allows Java services to advertise their presence/health to other Zenoh nodes.
- **Difficulty**: **Low** (~1–2 hours, since [`DeclareToken`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/DeclareToken.java) and [`UndeclareToken`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/UndeclareToken.java) already exist).

#### 5. User `Attachment` & Outbound HLC `Timestamp` API on `Put` + `Sample`
- **What is missing**:
  - [`Timestamp.now(ZenohId)`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/Timestamp.java) and [`Put`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Put.java)'s `timestamp` / `extensions` fields already exist on the wire layer, but [`PureJavaZenohPublisher`](pure-java/src/main/java/io/mdudel/zenoh/purejava/PureJavaZenohPublisher.java) always passes `null` for `timestamp` and `List.of()` for `extensions`.
  - In `zenoh`, `zenoh-pico`, and `zenoh-java`, callers can attach arbitrary metadata (`Attachment`, wire extension `id = 0x03` ZBuf on `Put`/`Del`/`Query`) and opt into automatic HLC timestamping (`Timestamp`), and subscribers can read `sample.attachment()` on [`Sample`](pure-java/src/main/java/io/mdudel/zenoh/purejava/session/Sample.java).
- **Necessity**: **Medium (P2)** — commonly used for passing trace IDs, headers, or source metadata alongside payloads without modifying the payload body.
- **Difficulty**: **Low** (~2–3 hours).

---

### Tier 3: Ergonomic & Secondary Transport Features (Lower Priority)

#### 6. Outbound `DeclareKeyExpr` Registration for High-Frequency Publishers
- **What is missing**: Inbound `DeclareKeyExpr` resolution is implemented, and outbound `Push` supports numeric `keyScope`, but `ZenohSession` currently sends the full key string on outbound publishes (`keyScope = 0`). Adding `session.declareKeyExpr(String keyExpr)` (and re-declaring on reconnect) shrinks outbound `Push` headers to a 1–2 byte varint scope ID.
- **Necessity**: **Medium–Low (P3)** — bandwidth optimization for high-rate publishers on long topic names.
- **Difficulty**: **Low** (~2 hours).

#### 7. Scout-to-Connect Auto-Discovery & Multi-Endpoint Failover
- **What is missing**: In `zenoh-java` and `zenoh-pico`, if no explicit `connect` endpoint is configured, the client automatically broadcasts a UDP multicast [`Scout`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Scout.java) (`WhatAmI.ROUTER | PEER`), picks the first reachable TCP/TLS/WS locator from the [`Hello`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Hello.java) reply, and connects to it—and supports a fallback list of `connectEndpoints` (`["tls/router1:7447", "tls/router2:7447"]`).
- **Necessity**: **Medium–Low (P3)** — convenient for zero-config LAN discovery and multi-router HA failover, and [`PureJavaZenohScout`](pure-java/src/main/java/io/mdudel/zenoh/purejava/scouting/PureJavaZenohScout.java) already exists.
- **Difficulty**: **Low** (~2 hours).

#### 8. Publisher Matching Listener (`Publisher.matchingListener`)
- **What is missing**: `zenoh`, `zenoh-pico`, and `zenoh-java` allow a publisher to know whether at least one subscriber is currently matching its key expression (so a producer can pause expensive work when nobody is listening). Under the hood, this is an [`Interest`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Interest.java) (`Mode.CURRENT_FUTURE`, `FLAG_SUBSCRIBERS | FLAG_AGGREGATE`) scoped to the publisher's key expression.
- **Necessity**: **Low–Medium (P3)**.
- **Difficulty**: **Low** (~2 hours, since [`Interest`](pure-java/src/main/java/io/mdudel/zenoh/purejava/wire/messages/Interest.java) is already implemented).

#### 9. Unicast UDP Session Transport (`udp/host:port`)
- **What is missing**: Running full Zenoh client sessions over unicast UDP (`DatagramChannel`) instead of TCP/TLS/WS.
- **Necessity**: **Low (P3)** — TCP, TLS, and WS cover almost all enterprise/accredited deployments.
- **Difficulty**: **Medium** (~1 day).

#### 10. QUIC Transport (`quic/host:port`), Peer/Router Mesh Mode, and Shared Memory (`ZShm`)
- **What is missing**:
  - `quic/host:port`: JDK 17 has no built-in QUIC stack in the standard library; supporting QUIC without JNI requires a third-party pure-Java library (e.g. `kwik`) in an optional submodule.
  - Peer-to-peer linkstate/gossip routing, router brokering, and POSIX shared memory (`ZShm`).
- **Necessity**: **Out of Scope / P4** — explicitly non-goals for a zero-dependency pure-Java client.
- **Difficulty**: **High to Very High**.

---

## 5. Summary Priority Matrix

| Rank | Capability | Status / Priority | Necessity / Impact | Difficulty (Pure Java) | Est. Effort |
| :---: | :--- | :---: | :--- | :---: | :---: |
| **✓** | **Multi-message `Frame` unbatching** | **DONE** | Prevents silent loss of 2nd+ messages in batched `Frame`s from `zenohd` | **Low** | Completed |
| **✓** | **Numeric KeyExpr mapping (`DeclareKeyExpr` / `UndeclareKeyExpr`)** | **DONE** | Receives data from `zenoh`/`zenoh-pico`/`zenoh-java` `declare_publisher` | **Low–Med** | Completed |
| **✓** | **Backport `AsterixIngest` additions (`PemLoader` EC fix, QoS, `TopicRegistry`, `ZenohConnection`, RSA Auth)** | **DONE** | Fixes `EC PRIVATE KEY` PEM loading; adds QoS, unified `ZenohConnection`, RSA auth, and multi-source `TopicRegistry` | **Low** | Completed |
| **✓** | **Auto-Reconnect & Subscription/Interest Re-declaration** | **DONE** | Keeps long-lived clients alive across `zenohd` restarts / network drops | **Medium** | Completed |
| **1** | **Transport Fragmentation (`Fragment` `0x06` send & receive)** | **P1** | Unlocks payloads >64 KB (`StreamFramer` 65,535-byte ceiling) | **Medium** | ~4–6 hrs |
| **2** | **Query / Queryable (`get` & `declareQueryable` + `@/**` Admin Space)** | **P1/P2** | Core Zenoh RPC verb + allows querying `zenohd` admin space for publishers | **Med–High** | ~1–2 days |
| **3** | **`Delete` operation (`Del` `0x02`) + `Sample.Kind.DELETE`** | **P2** | Completes Zenoh Pub/Sub verb pair (`PUT` + `DELETE`) | **Low** | ~2 hrs |
| **4** | **Outbound Liveliness Token API (`declareLivelinessToken`)** | **P2** | Presence/health advertisement across Zenoh nodes (wire codecs already done) | **Low** | ~1–2 hrs |
| **5** | **Attachments (`ext_attachment`) & Outbound HLC `Timestamp`** | **P2** | Metadata headers & source timestamps on published/received samples | **Low** | ~2–3 hrs |
| **6** | **Outbound `DeclareKeyExpr` publisher registration** | **P3** | Wire-size reduction for high-frequency publishers | **Low** | ~2 hrs |
| **7** | **Multi-Endpoint Failover & Scout Auto-Connect** | **P3** | Zero-config LAN discovery & multi-router HA failover | **Low** | ~2 hrs |
| **8** | **Publisher Matching Listener (`matchingListener`)** | **P3** | Lets publisher know when >=1 subscriber is listening | **Low** | ~2 hrs |
