# java-zenoh-publisher

A minimal, standalone **native Zenoh publishing client** for the JVM,
intended as a copy-and-hack **starter kit** for building your own
custom Java Zenoh client.

This was extracted from another project to distill the Zenoh Java Native Client so you get one small pure Java codebase you can easily leverage in your own Java projects:

* [`ZenohClient.java`](src/main/java/io/mdudel/zenoh/ZenohClient.java) —
  session + publisher lifecycle, TLS/mTLS config, org-prefix key
  resolution, per-subkey publisher cache, byte-array publish, metrics.
* [`ZenohPublisherApp.java`](src/main/java/io/mdudel/zenoh/ZenohPublisherApp.java) —
  thin CLI wrapper: parse args, connect, publish at a rate or from stdin.
* [`samples/`](samples/) — copy-paste-ready examples that use
  `ZenohClient` in your own code (start here!).

Built against **`zenoh-java 1.9.0`** (the fat JAR with bundled native
libraries for `x86_64` linux-gnu / apple-darwin / windows-msvc). Both
the Zenoh JAR and the Kotlin stdlib it needs at runtime are vendored
under [`vendor/repo/`](vendor/repo) so the build works air-gapped on
the first run.

---

## Quickstart (5 minutes)

### 1. Build

```bash
mvn -q clean package
```

Produces `target/java-zenoh-publisher-0.1.0-fat.jar` (~30 MB — includes
the native Zenoh libraries).

### 2. Publish something

```bash
java -jar target/java-zenoh-publisher-0.1.0-fat.jar \
    --endpoint=tcp/localhost:7447 \
    --key=demo/example/zenoh-java \
    --rate=1 \
    --message="hello from the starter"
```

### 3. See it arrive

Use any Zenoh subscriber — for example the `zenoh-python` z_sub tool
or the sample subscriber in this repo (see `samples/HelloSubscriber.java`).

```bash
# python
z_sub -k 'demo/example/**'
```

---

## Using `ZenohClient` in your own project

**This is the main event.** If you want to build your own Zenoh client
(publisher, subscriber, RPC responder, whatever), the recipe is:

### Step 1 — copy `ZenohClient.java` into your project

Or just add this repo as a Maven dependency once you install it locally:

```xml
<dependency>
  <groupId>io.mdudel</groupId>
  <artifactId>java-zenoh-publisher</artifactId>
  <version>0.1.0</version>
</dependency>
```

Also copy `vendor/repo/` into your project (or install its contents
into your local `~/.m2/repository`) so Maven can resolve `zenoh-java`
and `kotlin-stdlib`. See the `pom.xml` in this repo for the exact
`<repositories>` and `<dependency>` blocks you need — copy them as-is.

### Step 2 — build a client with the fluent builder

```java
import io.mdudel.zenoh.ZenohClient;
import java.nio.charset.StandardCharsets;

ZenohClient client = ZenohClient.builder()
        .connectEndpoint("tcp/localhost:7447")   // Zenoh router endpoint
        .keyExpr("demo/example/zenoh-java")       // where you publish
        .build();
```

Everything else on the builder is optional:

| Builder method              | What it does                                             | Default |
|-----------------------------|----------------------------------------------------------|---------|
| `.connectEndpoint(s)`       | Router endpoint (`tcp/`, `tls/`, `quic/`, `wss/`, …)     | `""` (peer-discovery mode) |
| `.keyExpr(s)`               | Base Zenoh key expression                                | `demo/example/zenoh-java` |
| `.org(s)`                   | Per-tenant prefix; published key becomes `org/keyExpr`   | `""` |
| `.rootCaCertPath(s)`        | Path to router's trust anchor PEM (TLS)                  | `""` |
| `.clientCertPath(s)`        | Client cert PEM (with `clientKeyPath` → enables mTLS)    | `""` |
| `.clientKeyPath(s)`         | Client private key PEM                                   | `""` |
| `.verifyHostname(b)`        | Verify router cert SAN/CN                                | `false` |

### Step 3 — start, publish, stop

```java
client.start();                                  // opens session, declares publisher
try {
    // simple publish — payload is arbitrary bytes
    client.publish("hello".getBytes(StandardCharsets.UTF_8));

    // per-subkey routing - publishes to demo/example/zenoh-java/sensor-42
    client.publish("sensor-42", payload);

    // metrics if you need them
    System.out.println("sent=" + client.getSentCount()
                     + " lastError=" + client.getLastError());
} finally {
    client.stop();                                // idempotent — safe to call twice
}
```

Or use it as an `AutoCloseable` and let the JVM handle it:

```java
try (ZenohClient client = ZenohClient.builder()
        .connectEndpoint("tcp/localhost:7447")
        .keyExpr("demo/greeting")
        .build()) {
    client.start();
    client.publish("world".getBytes(StandardCharsets.UTF_8));
}
```

### Step 4 — extend it for your use case

`ZenohClient` is deliberately **publisher-focused and stateless about
payload format**. That's the extraction point. Common extensions:

- **Subscriber:** copy the `Session` open + config code from
  `ZenohClient.start()` and swap `session.declarePublisher(…)` for
  `session.declareSubscriber(…, Callback)`. The
  [`samples/HelloSubscriber.java`](samples/HelloSubscriber.java) shows
  this exact pattern in ~60 lines.
- **Queryable / RPC:** same open + config, then
  `session.declareQueryable(…)` with a callback.
- **Structured payloads:** wrap `publish(byte[])` with a serializer
  (JSON, protobuf, Avro). See [`samples/JsonPublisher.java`](samples/JsonPublisher.java)
  for a JSON example using only the JDK - no third-party deps.
- **Reconnect logic:** wrap `start()` in a retry loop with backoff.
  Zenoh's own client will try to reconnect at the transport layer, but
  if `Zenoh.open()` itself throws (bad TLS material, router down at
  startup, etc.) you'll get an `IOException` from `start()` and it's
  up to you to retry.

**Rule of thumb:** if your extension needs to open a session, don't
call `Zenoh.open()` directly — you'll lose the endpoint-parse,
TLS-config, and warning-log logic. Just add a new method on
`ZenohClient` (or a subclass) that reuses `start()`'s session and
declares your own primitive on it.

---

## Endpoint syntax

| protocol | example                 | notes                         |
|----------|-------------------------|-------------------------------|
| `tcp/`   | `tcp/localhost:7447`    | plaintext, most common        |
| `udp/`   | `udp/router:7447`       | plaintext                     |
| `ws/`    | `ws/router:8080`        | plaintext WebSocket           |
| `tls/`   | `tls/router:7447`       | TLS; supports mTLS            |
| `quic/`  | `quic/router:7447`      | TLS over QUIC                 |
| `wss/`   | `wss/router:8080`       | TLS WebSocket                 |

Omit `--endpoint` entirely (or pass `""` to the builder) to use
peer-discovery mode (multicast).

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

Common gotchas (learned the hard way in production):

* Cert paths on a `tcp/` endpoint are **silently ignored**. The client
  logs a warning at `start()` if you do this.
* Most TLS Zenoh routers require mTLS and will close the handshake if
  you point at `tls/` without a client cert + key. The client logs a
  warning for that too.

---

## Repository layout

```
pom.xml                                                Parent Maven build (JDK 17, shade fat-jar)
src/main/java/io/mdudel/zenoh/ZenohClient.java         the extracted client
src/main/java/io/mdudel/zenoh/ZenohPublisherApp.java   CLI starter
src/test/java/io/mdudel/zenoh/...                      resolveKey() contract tests
samples/                                               independently-buildable examples
  README.md                                              overview + build recipe
  hello-publisher/                                       Maven project: minimal publisher
  hello-subscriber/                                      Maven project: minimal subscriber
  json-publisher/                                        Maven project: structured JSON payloads
  mtls-publisher/                                        Maven project: TLS + mTLS
  cot-streaming-publisher/                               Maven project: streaming CoT with TTL
vendor/repo/io/zenoh/zenoh-java/1.9.0/                 Zenoh Java fat JAR (~28MB)
vendor/repo/org/jetbrains/kotlin/...                   Kotlin stdlib jars
```

Each sample under `samples/` is its own Maven project with its own
`pom.xml` and `README.md`, buildable in isolation via `mvn package`.
Install the parent starter into `~/.m2` once with
`mvn -f pom.xml install`, then any sample builds and runs
standalone. See [`samples/README.md`](samples/README.md) for the
full recipe.

## Platform support

Bundled native libraries only cover **x86_64**. On ARM / other archs
`Zenoh.open()` throws `UnsatisfiedLinkError`, which the client catches
as `Throwable` and rethrows as `IOException` with a friendly message.
Building the native library for other architectures is documented in
the [upstream zenoh-java repo](https://github.com/eclipse-zenoh/zenoh-java).

## License

No license file is included by default. Note that
`vendor/repo/io/zenoh/zenoh-java-1.9.0.jar` is Eclipse Zenoh,
distributed under the Apache License 2.0 / EPL-2.0. Kotlin stdlib is
Apache 2.0.

Use at your own risk.


