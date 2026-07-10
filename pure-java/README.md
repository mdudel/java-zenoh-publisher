# java-zenoh-publisher-pure

**Pure-Java** publisher client for the Eclipse Zenoh 1.x wire protocol.
No JNI. No native binaries. Zero runtime dependencies beyond JDK 17.

Sibling module to the JNI-backed
[`java-zenoh-publisher`](../) in the same repo. Both expose (or will
expose) the same builder API, so switching between them at a call
site is a one-line change.

## Status: functional publisher (0.0.1-SNAPSHOT)

**Working.** As of Turn E, the publisher is end-to-end functional:
facade &rarr; session (handshake, KEEP_ALIVE, lease watchdog) &rarr;
transport (TCP / TLS / WS / WSS) &rarr; wire codec. See
[`../samples/pure-java-simple-publisher/`](../samples/pure-java-simple-publisher/)
for a runnable minimal example.

_Historical note: earlier snapshots of this file said_ "MVP
scaffolding, not yet functional" — that's obsolete as of Turn E.

The original scaffolding shipped in Turn A:

- Module skeleton (`pom.xml`, package layout, license)
- The public `PureJavaZenohPublisher` API surface with builder,
  lifecycle methods, and getters - matches the shape of the sibling
  `io.mdudel.zenoh.ZenohClient` for drop-in swappability
- `wire.VarInt` - unsigned LEB128 codec, fully implemented + tested
- `wire.KeyExpr` - org-prefix key resolver, ported verbatim from
  the JNI publisher so effective keys are byte-identical

As of Turn E, `.start()` opens a real session and `.publish(...)`
delivers real Zenoh frames to a real router. The Turn A stubs
are gone.

Roadmap for follow-up turns:

| Turn | Adds |
|------|------|
| **A (done)**  | Scaffolding, VarInt, KeyExpr, API surface, tests |
| **B1 (done)** | Codec primitives (WBuf, RBuf, ZenohId, Extension chain, WhatAmI) + INIT message |
| **B2 (done)** | OPEN, CLOSE, KEEP_ALIVE transport messages |
| **B3 (done)** | FRAME transport message + PUSH network carrier + PUT zenoh payload + Encoding + Timestamp |
| **C1 (done)** | Plain TCP transport with 2-byte LE stream framing + one reader thread per link |
| **C2 (done)** | TLS + optional mTLS via `javax.net.ssl.SSLSocket`, hostname verification on by default, `TlsConfig` builder |
| **C3 (done)** | WS + WSS via `java.net.http.WebSocket` (JDK stdlib), fragmented-message reassembly, TLS via shared `TlsConfig` |
| **D (done)**  | `ZenohSession` state machine (CREATED→CONNECTING→OPENING→OPEN→CLOSING→CLOSED) + INIT/OPEN handshake ceremony + KEEP_ALIVE scheduler + lease-expiry watchdog + graceful CLOSE emission |
| **E (this)**  | `PureJavaZenohPublisher` wired to `ZenohSession` + endpoint parser (tcp/tls/ws/wss) + runnable CLI + `samples/pure-java-simple-publisher/` |
| **F**         | End-to-end interop verification against a real `zenohd` (needs a pcap capture) |

## Why this module exists

The sibling `java-zenoh-publisher` module works, but bundles a
28 MB Rust-compiled JNI native library (`libzenoh_jni.so` /
`.dll` / `.dylib`) inside the `zenoh-java-1.9.0` jar. That native
binary is:

- **A source of accreditation friction** in environments where every
  binary in the deployment must be signed off, and shipping opaque
  Rust artifacts without the corresponding source code is a blocker.
- **Restricted to `x86_64`** on Linux / macOS / Windows. No ARM64,
  no musl, no Android, no z/OS.
- **Not `-Djava.security.manager` friendly** (though the SM is
  itself deprecated in JDK 17+, so this matters less over time).

This module trades those constraints for a smaller feature set (only
publish, only TCP/TLS/WSS, only Zenoh 1.x client mode). If those
tradeoffs are wrong for your use case, use the JNI-backed sibling.

## Supported transports (planned)

| Endpoint         | Transport                | Deps                        |
|------------------|--------------------------|-----------------------------|
| `tcp/host:port`  | plain TCP                | JDK only (`java.net.Socket`) |
| `tls/host:port`  | TLS + optional mTLS      | JDK only (`javax.net.ssl.SSLSocket`) |
| `wss/host:port`  | WebSocket over TLS       | JDK only (`java.net.http.WebSocket`) |
| `ws/host:port`   | WebSocket plaintext      | JDK only (same, freebie) |

**TLS key material**: both **PEM** (`rootCa.pem` + `client.pem` + `client.key`)
and **PKCS12** (`*.p12` / `*.pfx`) are accepted; the file extension
picks the loader. PEM matches the JNI sibling's argument shape
(`rootCaCertPath` / `clientCertPath` / `clientKeyPath`), so the two
publisher facades are drop-in swappable for mTLS deployments.
Supported private-key PEM formats: PKCS#8 unencrypted
(`-----BEGIN PRIVATE KEY-----`, the modern default) and PKCS#1
(`-----BEGIN RSA PRIVATE KEY-----`, what legacy `openssl req` emits
— auto-wrapped in-memory so no `openssl pkcs8` conversion needed).
Encrypted PKCS#8 keys are rejected with a message pointing at the
conversion command.

**Deferred / out of scope**:

- **UDP** - Zenoh's UDP transport uses a distinct framing model.
  Marginal value for a publisher-only client; will land after the
  subscriber follow-up if there is real demand.
- **QUIC** - No pure-Java QUIC in JDK 17. The best pure-Java
  option is [Kwik](https://github.com/ptrd/kwik) (~40k LOC third-
  party dependency, Apache-2.0). Deferred deliberately to keep the
  core module's dependency count at zero. If QUIC becomes a hard
  requirement, it would land as a separate opt-in submodule
  (`pure-java-quic`) so users who need the smallest accreditation
  surface can skip it.

## Design constraints

| Constraint | Choice |
|------------|--------|
| Threading  | Blocking I/O, one reader thread per session. Publishes serialise on a per-session `synchronized` lock. Scale by creating multiple publisher instances, not by tuning one. |
| Logging    | `java.lang.System.Logger` (JDK built-in). Users bridge to SLF4J / Log4j / Logback / JUL via a `System.LoggerFinder` on the classpath. See [Bridging logs](#bridging-logs) below. |
| Runtime deps | **Zero.** JDK 17 stdlib only. Test scope: JUnit 5. |
| License    | Apache 2.0. Clean-room implementation of the Eclipse Zenoh 1.x public wire protocol; not derived from any Zenoh source code. |

## Building

```bash
cd pure-java
mvn clean package
```

Produces `target/java-zenoh-publisher-pure-0.0.1-SNAPSHOT.jar` (a
few tens of KB - notice how small a jar with no shaded dependencies
actually is).

Run the CLI stub:

```bash
java -jar target/java-zenoh-publisher-pure-0.0.1-SNAPSHOT.jar
```

Right now that just prints a status message and points at this
README.

## Running the tests

```bash
mvn test
```

The tests cover:

- **`VarIntTest`** - round-trip encoding/decoding, boundary values
  (0, 127, 128, 16383, 16384, `Long.MAX_VALUE`), truncated / malformed
  input, stream vs byte-array API parity.
- **`KeyExprTest`** - the org-prefix resolver, with the same
  expected values as the JNI publisher's `ZenohClientResolveKeyTest`
  so drift between the two implementations shows up as a test failure.
- **`WBufRBufTest`** - byte-level primitives (u8, little-endian u16,
  varints, length-prefixed byte/string), slicing, underflow handling.
- **`ZenohIdTest`** - length constraints (1..16 bytes), the
  `encoded_length = actual - 1` nibble convention, random-ZID generation,
  hex `toString()`.
- **`ExtensionTest`** - the TLV chain codec: each of the three body
  encodings (Unit, Z64, ZBuf), the mandatory (M) flag, the more-follows
  (Z) flag being set on all but the last extension in a chain, the
  reserved encoding (0b11) being rejected, and the crucial forward
  compatibility property that a reader can skip unknown extensions.
- **`InitTest`** - hand-crafted INIT bytes checked against the reference
  Rust source's ASCII wire diagram: header/version/lenWai byte layout,
  WhatAmI 2-bit encoding, ZID-length nibble, cookie framing on InitAck,
  extension chain presence gated by the Z flag.
- **`OpenTest`** - OpenSyn encode (varint lease + varint initial SN +
  length-prefixed cookie), OpenAck decode with the T flag toggling
  seconds-vs-milliseconds unit for the lease, extension chain gated by
  the Z flag, refusal to decode an OpenSyn-shaped payload as an OpenAck.
- **`CloseTest`** - all 8 reason codes match the spec, S flag toggles
  link-vs-session scope, extension chain gated by Z, round-trip.
- **`KeepAliveTest`** - single-byte encoding when no extensions,
  Z-flag gating for the extension chain, round-trip.
- **`FrameTest`** - transport FRAME container: R flag toggles reliable vs
  best-effort channel, Z flag gates the extension chain, sn is a varint,
  payload is opaque bytes carrying serialised network messages, helper
  `ofPush(sn, reliable, push)` embeds an encoded PUSH verbatim, round-trip
  including a FRAME > PUSH > PUT nesting.
- **`PushTest`** - network PUSH carrier: N/M/Z flag combinations, u16 key
  scope range check, UTF-8 suffix round-trip, standard publisher shape
  from `ofPut(key, put)` (N=1, M=0, Z=0), rejection of wrong network-message
  id.
- **`PutTest`** - zenoh PUT payload: T/E/Z flag combinations, header layout
  for the MVP bare-bytes shape, timestamp position (before encoding),
  `Encoding.EMPTY` clears the E flag, round-trip with all three of
  {timestamp, encoding, unknown-extension} at once, 8 KB payload for varint
  length boundary coverage.
- **`EncodingTest`** - Encoding field: bare-id shifts left by 1, S-flag on
  schema, `EMPTY` encodes as a single zero byte, schema length capped at 255,
  UTF-8 schemas round-trip exactly.
- **`TimestampTest`** - HLC timestamp: varint HLC time + length-prefixed
  ZenohId, NTP epoch offset matches RFC 868 (UNIX epoch = 2208988800 NTP
  seconds), `Timestamp.now(id)` round-trips through `toInstant()` within
  nanosecond tolerance.
- **`StreamFramerTest`** - the 2-byte little-endian length prefix used
  on TCP/TLS/WSS: writing and reading empty / small / boundary /
  max-sized (65 535 B) frames, oversized-payload rejection, EOF at
  a frame boundary vs mid-length vs mid-payload each surfacing with
  a distinct message, reassembly across pathological 1-byte-per-`read`
  streams, back-to-back multi-frame streams.
- **`PureJavaZenohPublisherTest`** - end-to-end facade tests via the
  same `LoopbackZenohRouter` (now public for cross-package reuse).
  16 tests covering: start→publish→stop round-trip with server-side
  wire assertions, org-prefix applied to effective key, subKey
  appending under the effective key, publish-before-start /
  publish-after-close rejected, double-start idempotent, close
  idempotent, empty/malformed/invalid-port/unsupported-scheme
  endpoints rejected at start, `buildTransport()` returns the right
  `Transport` subtype for each of `tcp/`, `ws/`, `ws://...`,
  `tls/`, and the CLI `main()` publishes to the loopback router
  end-to-end.
- **`ZenohSessionTest`** - end-to-end session integration via a
  loopback Zenoh router mock (`LoopbackZenohRouter`, ~330 LOC under
  `src/test/java/`, still no third-party dep). Covers: 4-message
  handshake reaches OPEN and captures remote `ZenohId`, double
  `open()` rejected, publish-before-open rejected, publish emits
  FRAME containing PUSH containing PUT with correct key/payload,
  monotonic sequence numbers across sequential publishes, KEEP_ALIVE
  fires when outbound quiet, KEEP_ALIVE skipped when outbound busy,
  server-driven CLOSE tears the session down, `close()` emits CLOSE
  frame to peer, `close()` idempotent, publish-after-close rejected,
  handshake timeout, garbage InitAck rejected, lease expiry closes
  session when server goes silent, null-transport rejected, invalid
  lease/timeout rejected, server-side lease override adopted.
- **`WsTransportTest`** - loopback WebSocket integration via a small
  test-only RFC 6455 server (`LoopbackWebSocketServer`, ~250 LOC
  under `src/test/java/`, still no third-party dep). Covers: binary
  round-trip, multi-frame in-order delivery, server-side fragmented
  message reassembly (10 KB payload chunked into 1 KB WebSocket
  fragments), two concurrent senders serialise on the write lock,
  server clean-close surfaces as `null`+`isOpen()`-false, unexpected
  text frame trips the reader-error path, oversized batch rejected
  at the WS layer, max-sized (65 535 B) batch round-trips, `wss://`
  round-trip via the same PKCS12 keystores as the TLS tests,
  invalid scheme/host/port rejected at construction,
  `wss://` requires a `TlsConfig`, `ws://` rejects a non-null
  `TlsConfig`, connect-refused surfaces as `TransportException`,
  send-on-closed rejects, `close()` is idempotent.
- **`TlsTransportTest`** - loopback `SSLServerSocket` integration:
  round-trip over TLS 1.3, mutual-TLS handshake succeeds with client
  cert presented and server-side `getPeerCertificates()` reports the
  right CN, mutual-TLS fails when the client omits its keystore
  (pinned to TLSv1.2 so the failure lands at handshake time rather
  than post-handshake auth), unknown server cert rejected by an
  empty trust store, hostname verification failure for a wrong-host
  connect, hostname verification toggle preserves happy path,
  TLSv1.2 fallback works when protocol is pinned, oversized batch
  rejected at TLS layer, invalid port rejected at construction,
  null TlsConfig rejected. Backed by pre-generated PKCS12 keystores
  under `src/test/resources/` (`server.p12`, `client.p12`,
  `server-trust.p12`, `client-trust.p12` — 3650-day self-signed
  certs, SAN covers dns:localhost + ip:127.0.0.1).
- **`TcpTransportTest`** - loopback `ServerSocket` integration:
  `connect()`/`send()`/`receive()` round-trip against a real socket
  peer, connect-refused surfaces as `TransportException`, oversized
  batch rejected without closing the transport, quiet-server
  `receive(timeout)` returns `null`, remote clean close surfaces as
  `null` and flips `isOpen()` to false, two concurrent sender threads
  serialise cleanly on the write lock (no interleaved frames on the
  wire), `close()` is idempotent, invalid ports rejected at
  construction, double-`connect()` rejected, multi-frame in-order
  delivery.

## Bridging logs

By default the publisher writes to `java.lang.System.Logger`, which the
JDK backs with `java.util.logging`. To route logs into an application's
existing SLF4J-based logging stack, add the SLF4J JDK-platform-logging
bridge to the classpath:

```xml
<dependency>
  <groupId>org.slf4j</groupId>
  <artifactId>slf4j-jdk-platform-logging</artifactId>
  <version>2.0.13</version>
</dependency>
<dependency>
  <groupId>org.slf4j</groupId>
  <artifactId>slf4j-simple</artifactId>   <!-- or logback-classic, log4j-slf4j2-impl, ... -->
  <version>2.0.13</version>
</dependency>
```

SLF4J's `slf4j-jdk-platform-logging` registers a
`java.lang.System.LoggerFinder` service that delegates every
`System.Logger` call to the SLF4J API, at which point the standard
SLF4J bindings take over. No code changes are needed on the publisher
side &mdash; the two `<dependency>` blocks above are the only
addition, and they stay on the *user's* classpath, not this module's.

This module deliberately does not depend on SLF4J directly, so
accreditation environments that forbid extra runtime jars can keep
the default `java.util.logging` backend without touching anything.

## Non-goals (permanent, not "yet")

- Full Zenoh peer / router modes. This is a **client-mode publisher
  only**. Ever.
- Wildcard-intersect key matching. Publishers publish under concrete
  keys; matching is a subscriber-side concern.
- Zenoh Storages, Queryables, Liveliness tokens.
- Wire protocol version negotiation across pre-1.x Zenoh routers.
  1.x only.

## Not the same jar as the JNI publisher

Different Maven coordinates so they can coexist:

|                        | JNI publisher                              | Pure Java publisher                          |
|------------------------|--------------------------------------------|----------------------------------------------|
| `groupId`              | `io.mdudel`                                | `io.mdudel`                                  |
| `artifactId`           | `java-zenoh-publisher`                     | `java-zenoh-publisher-pure`                  |
| Class                  | `io.mdudel.zenoh.ZenohClient`              | `io.mdudel.zenoh.purejava.PureJavaZenohPublisher` |
| Runtime deps           | zenoh-java 1.9.0, kotlin-stdlib 1.9.10     | none                                         |
| Fat jar size           | ~30 MB                                     | expected < 200 KB                            |
| Platform               | x86_64 linux-gnu / apple-darwin / win-msvc | any JDK 17                                   |
| Native binaries        | `libzenoh_jni.{so,dylib,dll}` bundled      | none                                         |
