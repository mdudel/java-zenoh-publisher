# java-zenoh-publisher-pure

**Pure-Java** publisher client for the Eclipse Zenoh 1.x wire protocol.
No JNI. No native binaries. Zero runtime dependencies beyond JDK 17.

Sibling module to the JNI-backed
[`java-zenoh-publisher`](../) in the same repo. Both expose (or will
expose) the same builder API, so switching between them at a call
site is a one-line change.

## Status: MVP scaffolding (0.0.1-SNAPSHOT)

**Not yet functional.** This first drop lays down:

- Module skeleton (`pom.xml`, package layout, license)
- The public `PureJavaZenohPublisher` API surface with builder,
  lifecycle methods, and getters - matches the shape of the sibling
  `io.mdudel.zenoh.ZenohClient` for drop-in swappability
- `wire.VarInt` - unsigned LEB128 codec, fully implemented + tested
- `wire.KeyExpr` - org-prefix key resolver, ported verbatim from
  the JNI publisher so effective keys are byte-identical

Calling `.start()` or `.publish(...)` today throws
`UnsupportedOperationException`. The class exists so the design can
be reviewed before the wire code lands.

Roadmap for follow-up turns:

| Turn | Adds |
|------|------|
| **A (this)**  | Scaffolding, VarInt, KeyExpr, API surface, tests |
| **B**         | Zenoh wire message codec (Init/Open/DeclarePublisher/Push/Put/KeepAlive/Close) |
| **C**         | Transport layer: TCP -> TLS/mTLS -> WSS/ws |
| **D**         | Session state machine + KeepAlive thread + clean shutdown |
| **E**         | Wire together `PureJavaZenohPublisher.start()`/`.publish()`, ship a sample project |
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
| Logging    | `java.lang.System.Logger` (JDK built-in). Users bridge to SLF4J / Log4j / Logback / JUL via a `System.LoggerFinder` on the classpath. |
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
