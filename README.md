# java-zenoh-publisher

A minimal, standalone **native Zenoh publishing client** for the JVM,
intended as a copy-and-hack starter kit for building a custom Java Zenoh
client.

Extracted from the [almondmalt] `ZenohNativeEmitter` and stripped of
application-specific hooks (org.json, ASTERIX/CoT format code, servlet
plumbing) so you get one small class you can actually read:

* [`ZenohClient.java`](src/main/java/io/mdudel/zenoh/ZenohClient.java) —
  session + publisher lifecycle, TLS/mTLS config, org-prefix key
  resolution, per-subkey publisher cache, byte-array publish, metrics.
* [`ZenohPublisherApp.java`](src/main/java/io/mdudel/zenoh/ZenohPublisherApp.java) —
  thin CLI wrapper: parse args, connect, publish at a rate or from stdin.

Built against **`zenoh-java 1.9.0`** (the fat JAR with bundled native
libraries for `x86_64` linux-gnu / apple-darwin / windows-msvc). Both the
Zenoh JAR and the Kotlin stdlib it needs at runtime are vendored under
[`vendor/repo/`](vendor/repo) so the build works air-gapped on the first
run.

## Quickstart

```bash
mvn -q clean package
java -jar target/java-zenoh-publisher-0.1.0-fat.jar \
    --endpoint=tcp/localhost:7447 \
    --key=demo/example/zenoh-java \
    --rate=1 \
    --message="hello from the starter"
```

On the receiving side, use any Zenoh subscriber (e.g. `zenohd` REST plugin,
`zenoh-python` `z_sub`, or your own subscriber):

```bash
# python
z_sub -k 'demo/example/**'
```

## Endpoint syntax

| protocol | example                 | notes                |
|----------|-------------------------|----------------------|
| `tcp/`   | `tcp/localhost:7447`    | plaintext, most common |
| `udp/`   | `udp/router:7447`       | plaintext            |
| `ws/`    | `ws/router:8080`        | plaintext WebSocket  |
| `tls/`   | `tls/router:7447`       | TLS; supports mTLS   |
| `quic/`  | `quic/router:7447`      | TLS over QUIC        |
| `wss/`   | `wss/router:8080`       | TLS WebSocket        |

Omit `--endpoint` entirely to use peer-discovery mode (multicast).

## TLS / mTLS

Any endpoint whose protocol starts with `tls/`, `quic/`, or `wss/`
triggers the TLS config block. Provide any subset of:

```
--root-ca=/path/to/ca.pem            # trust anchor for the router cert
--client-cert=/path/to/client.pem    # your client certificate
--client-key=/path/to/client.key     # your client private key
--verify-hostname=true               # verify SAN/CN of router cert
```

If **both** `--client-cert` and `--client-key` are set, mTLS
(`enable_mtls=true`) is turned on automatically.

Common gotchas (learned the hard way — see the almondmalt lessons file):

* Cert paths on a `tcp/` endpoint are **silently ignored**. The client
  logs a warning at `start()` if you do this.
* Most TLS Zenoh routers (including EFDI-style ones) require mTLS and
  will close the handshake if you point at `tls/` without a client
  cert + key. The client logs a warning for that too.

## Programmatic use

```java
ZenohClient client = ZenohClient.builder()
        .connectEndpoint("tls/router.example.com:7447")
        .keyExpr("radar/cat062")
        .org("acme")                       // → published to acme/radar/cat062
        .rootCaCertPath("/etc/pki/ca.pem")
        .clientCertPath("/etc/pki/me.pem")
        .clientKeyPath("/etc/pki/me.key")
        .verifyHostname(true)
        .build();

client.start();
try {
    client.publish("hello".getBytes(StandardCharsets.UTF_8));
    // per-subkey routing (e.g. one publisher per trackId):
    client.publish("track-42", payload);
} finally {
    client.stop();
}
```

## Repository layout

```
pom.xml                                         Maven build (JDK 17, shade fat-jar)
src/main/java/io/mdudel/zenoh/ZenohClient.java  the extracted client
src/main/java/io/mdudel/zenoh/ZenohPublisherApp.java  CLI starter
src/test/java/io/mdudel/zenoh/…                 resolveKey() contract tests
vendor/repo/io/zenoh/zenoh-java/1.9.0/          Zenoh Java fat JAR (~28MB)
vendor/repo/org/jetbrains/kotlin/…              Kotlin stdlib jars
```

## Platform support

Bundled native libraries only cover **x86_64**. On ARM / other archs
`Zenoh.open()` throws `UnsatisfiedLinkError`, which the client catches
as `Throwable` and rethrows as `IOException` with a friendly message.
Building the native library for other architectures is documented in the
[upstream zenoh-java repo](https://github.com/eclipse-zenoh/zenoh-java).

## License

Your call — no license file is included by default. Note that
`vendor/repo/io/zenoh/zenoh-java-1.9.0.jar` is Eclipse Zenoh, distributed
under the Apache License 2.0 / EPL-2.0. Kotlin stdlib is Apache 2.0.

## Origin

Distilled from the almondmalt (USAREUR G6 MCSD radar/web) project's
`ZenohNativeEmitter`, which grew organically alongside real deployments
against the EFDI Zenoh fabric. Notable design choices carried over:

* **Explicit `mode: "client"`** when an endpoint is set — otherwise
  Zenoh spends ~5 s on multicast peer discovery before giving up.
* **Single JSON block for TLS** — Zenoh 1.x rejects piecemeal
  `transport/link/tls/*` inserts once mTLS is involved.
* **Loud warnings** for the two silent-failure patterns
  (`tcp/` + certs, `tls/` without mTLS material) that cost the most
  hours in production incident triage.
* **Per-subkey publisher cache** so long-running publishers don't
  re-declare on every `put()`.

[almondmalt]: internal.
