# STEPxSTEP: build a Zenoh subscriber with the pure-Java client

A hands-on walkthrough. Empty directory in, running subscriber out.
No JNI, no native binaries, no third-party runtime dependencies —
JDK 17 and Maven are the only things you need on your machine.

**What you'll end up with:**

* A minimal Maven project called `my-zenoh-subscriber`
* One class `MySubscriber` that connects to a Zenoh router,
  subscribes to a key expression, and prints one line per received
  message
* A runnable fat jar you can `java -jar` against any Zenoh 1.x router

**What this guide assumes you already have:**

* JDK 17+ on your `PATH` (`java -version` and `javac -version` both
  work)
* Maven 3.6+ on your `PATH` (`mvn -v` works)
* A running Zenoh router somewhere reachable, or the intent to start
  one — [zenohd](https://github.com/eclipse-zenoh/zenoh/releases) or
  `docker run --rm -it -p 7447:7447 eclipse/zenoh` both work

If the router is on the same box, `tcp/localhost:7447` is the
default endpoint and you can leave endpoints alone.

---

## Step 0. Decide how to consume the pure-java client

You have two options. Pick one and stick with it — the rest of the
guide splits into a **Path A** and a **Path B** where they differ.

| Option | When to use | Downside |
|---|---|---|
| **Path A — Maven dependency** (recommended) | You have `mvn install` access to your own Maven repo or `~/.m2/`, and you're happy pinning to a version. | Requires the pure-java jar to be resolvable by Maven at build time. |
| **Path B — Copy the sources into your project** | Air-gapped build, must ship the source alongside your code for accreditation, want to fork and modify. | You own the sources now — updating means re-copying. |

Both paths produce the same runtime behaviour. Path A is what most
people should do.

---

## Step 1. Create an empty Maven project

Anywhere on disk:

```bash
mkdir -p my-zenoh-subscriber/src/main/java/com/example
cd my-zenoh-subscriber
```

Create a `pom.xml` at the project root — **use the block that
matches the path you picked in Step 0**.

### Path A — `pom.xml` (Maven dependency)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>com.example</groupId>
  <artifactId>my-zenoh-subscriber</artifactId>
  <version>0.1.0</version>
  <packaging>jar</packaging>

  <properties>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <main.class>com.example.MySubscriber</main.class>
  </properties>

  <dependencies>
    <!-- The pure-Java Zenoh client. Zero transitive runtime deps. -->
    <dependency>
      <groupId>io.mdudel</groupId>
      <artifactId>java-zenoh-publisher-pure</artifactId>
      <version>0.0.1-SNAPSHOT</version>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.13.0</version>
      </plugin>

      <!-- Runnable fat jar. Puts Main-Class in the manifest and
           shades the pure-java module into the final artifact. -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.5.1</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
              <transformers>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                  <mainClass>${main.class}</mainClass>
                </transformer>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
              </transformers>
              <filters>
                <filter>
                  <artifact>*:*</artifact>
                  <excludes>
                    <exclude>META-INF/*.SF</exclude>
                    <exclude>META-INF/*.DSA</exclude>
                    <exclude>META-INF/*.RSA</exclude>
                  </excludes>
                </filter>
              </filters>
              <finalName>${project.artifactId}-${project.version}-fat</finalName>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

Then **install the pure-java library into your local Maven cache**
so the `<dependency>` block above can resolve it. From a clone of
[`java-zenoh-publisher`](https://github.com/mdudel/java-zenoh-publisher):

```bash
git clone https://github.com/mdudel/java-zenoh-publisher.git
cd java-zenoh-publisher
mvn -f pure-java/pom.xml install
cd -   # back to my-zenoh-subscriber
```

> **Every time you `git pull` the upstream repo, rerun
> `mvn -f pure-java/pom.xml install`** before rebuilding your
> project. Maven has no way to know the sources changed — it will
> silently reuse the stale jar in `~/.m2/repository/` and give you
> a bewildering `cannot find symbol: class X` for any class added
> since your last install.

### Path B — `pom.xml` (sources copied in)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>com.example</groupId>
  <artifactId>my-zenoh-subscriber</artifactId>
  <version>0.1.0</version>
  <packaging>jar</packaging>

  <properties>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <main.class>com.example.MySubscriber</main.class>
  </properties>

  <!-- No <dependencies> for Path B: the pure-java code will live
       directly under src/main/java. JDK 17 stdlib is the only
       runtime dependency, and that comes from the JRE. -->

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.13.0</version>
      </plugin>

      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-jar-plugin</artifactId>
        <version>3.4.1</version>
        <configuration>
          <archive>
            <manifest>
              <mainClass>${main.class}</mainClass>
            </manifest>
          </archive>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

Then **copy the pure-java sources into your project**:

```bash
# From your my-zenoh-subscriber directory:
git clone https://github.com/mdudel/java-zenoh-publisher.git /tmp/jzp
cp -r /tmp/jzp/pure-java/src/main/java/io src/main/java/
# Optional: preserve licence + attribution
cp /tmp/jzp/pure-java/LICENSE LICENSE
```

You should now have `src/main/java/io/mdudel/zenoh/purejava/...`
sitting next to `src/main/java/com/example/` in your project.

> The pure-java module has zero third-party runtime dependencies, so
> the copy-in path is genuinely as simple as this. Nothing else
> needs to come along.

---

## Step 2. Write the subscriber main class

Create `src/main/java/com/example/MySubscriber.java`. Same file for
both paths:

```java
package com.example;

import io.mdudel.zenoh.purejava.PureJavaZenohSubscriber;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Minimal Zenoh subscriber built on the pure-Java client. Connects
 * to a router, subscribes to a key expression, prints one line per
 * received message. Runs until Ctrl-C, or exits after an optional
 * timeout.
 *
 * <p>Positional args (all optional):
 * <pre>
 *   java -jar target/my-zenoh-subscriber-0.1.0-fat.jar
 *     [endpoint]         default: tcp/localhost:7447
 *     [keyExpr]          default: demo/**
 *     [timeoutSeconds]   default: 0 (run until Ctrl-C)
 * </pre>
 */
public final class MySubscriber {

    public static void main(String[] args) throws Exception {
        // 1. Parse args (all optional, defaults kick in from the left).
        String endpoint       = args.length > 0 ? args[0] : "tcp/localhost:7447";
        String keyExpr        = args.length > 1 ? args[1] : "demo/**";
        long   timeoutSeconds = args.length > 2 ? Long.parseLong(args[2]) : 0L;

        System.out.println("[my-subscriber] connecting to " + endpoint
                + " subscribing to " + keyExpr
                + (timeoutSeconds > 0 ? " for " + timeoutSeconds + "s"
                                      : " (Ctrl-C to stop)"));

        // 2. Latch so Ctrl-C (SIGINT) tears the session down cleanly
        //    instead of leaving a half-open socket on the router.
        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(stop::countDown));

        // 3. try-with-resources guarantees close() runs even on exceptions,
        //    which sends a proper Zenoh CLOSE frame to the router.
        try (PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint(endpoint)
                .build()) {

            // 4. start() opens the TCP/TLS/WS transport and completes
            //    the 4-message Zenoh handshake (INIT -> INIT_ACK ->
            //    OPEN_SYN -> OPEN_ACK). Throws IOException on failure.
            sub.start();
            System.out.println("[my-subscriber] session OPEN");

            // 5. Push-style subscribe: your lambda runs on a fresh
            //    daemon thread inside the subscriber every time a
            //    matching sample arrives.
            //
            //    For pull-style, use sub.subscribe(keyExpr) and call
            //    subscription.take() / .poll(timeout) from a thread
            //    you control.
            sub.subscribeAndConsume(keyExpr, sample -> {
                System.out.println("[my-subscriber] "
                        + sample.key() + " -> " + sample.payloadAsString());
            });

            // 6. Block until either the timeout elapses or Ctrl-C fires.
            if (timeoutSeconds > 0) {
                stop.await(timeoutSeconds, TimeUnit.SECONDS);
            } else {
                stop.await();
            }

            System.out.println("[my-subscriber] shutting down"
                    + " (received=" + sub.getReceivedCount() + ")");
        }
        // try-with-resources auto-invokes sub.close() here, which
        // sends CLOSE to the router and joins the reader thread.
    }
}
```

### What each block does

* `builder().connectEndpoint(...)` — supports `tcp/`, `tls/`, `ws/`,
  `wss/`. For TLS/mTLS add `.rootCaCertPath(...)`, `.clientCertPath(...)`,
  `.clientKeyPath(...)` on the builder. See [`pure-java-mtls-subscriber`](../samples/pure-java-mtls-subscriber/)
  for the full mTLS example.
* `start()` — opens the transport and runs the Zenoh handshake.
  Blocks until the session is OPEN or throws `IOException`. Call
  once.
* `subscribeAndConsume(keyExpr, onSample)` — push-style. Your lambda
  runs on the subscriber's internal daemon thread; keep it fast or
  hand samples off to your own executor.
* `subscribe(keyExpr)` — pull-style. Returns a `Subscription` with
  `take()` (blocks) and `poll(timeout, unit)` (returns `null` on
  timeout).
* `Sample.key()` — the concrete key the sample was published under
  (never the wildcard).
* `Sample.payloadAsString()` — UTF-8 decoded convenience. For bytes,
  use `sample.payload()` which returns a defensive copy.
* `close()` (or the end of the try-with-resources block) — sends a
  Zenoh CLOSE frame, joins the reader thread, releases the socket.
  Idempotent.

### Key-expression syntax cheat-sheet

| Pattern | Matches |
|---|---|
| `sensors/room1/temp` | Exactly that one key. |
| `sensors/*/temp` | One-chunk wildcard: `sensors/room1/temp`, `sensors/kitchen/temp`, but not `sensors/x/y/temp`. |
| `sensors/**` | Zero-or-more-chunks wildcard: `sensors`, `sensors/room1`, `sensors/room1/temp`, etc. |
| `**` | Everything flowing through the router. |
| `log/$*Error` | Sub-chunk wildcard: `log/Error`, `log/FatalError`, `log/SoftError`. |

---

## Step 3. Build the project

From the `my-zenoh-subscriber` directory:

```bash
mvn -q clean package
```

Look in `target/`:

* **Path A** produces `target/my-zenoh-subscriber-0.1.0-fat.jar`
  (~60 KB — includes the shaded pure-java module).
* **Path B** produces `target/my-zenoh-subscriber-0.1.0.jar`
  (~60 KB — pure-java classes are already yours, so nothing gets
  shaded, but the manifest still has `Main-Class`).

If the build fails with `cannot find symbol: class PureJavaZenohSubscriber`
on Path A, you skipped `mvn -f pure-java/pom.xml install` or the
local Maven cache went stale after a `git pull` — go back to Step 1
Path A and rerun it.

---

## Step 4. Run it

### Start a Zenoh router (if you don't already have one)

Fastest option, no install:

```bash
docker run --rm -it -p 7447:7447 eclipse/zenoh
```

Or download a native `zenohd` from
[the Zenoh releases page](https://github.com/eclipse-zenoh/zenoh/releases)
and run `./zenohd`.

### Run the subscriber

**Path A:**

```bash
java -jar target/my-zenoh-subscriber-0.1.0-fat.jar
```

**Path B:**

```bash
java -jar target/my-zenoh-subscriber-0.1.0.jar
```

You should see:

```
[my-subscriber] connecting to tcp/localhost:7447 subscribing to demo/** (Ctrl-C to stop)
[my-subscriber] session OPEN
```

...and then it sits there waiting for matching messages.

### Publish something to prove it works

In another terminal, use any Zenoh publisher. Rust CLI:

```bash
z_put -k demo/hello -v 'hello from another terminal'
```

Python:

```bash
python -c "import zenoh; s=zenoh.open(); s.put('demo/hello','hi'); s.close()"
```

Or the pure-java sibling sample (from the `java-zenoh-publisher`
clone you already have):

```bash
cd /path/to/java-zenoh-publisher
mvn -q -f samples/pure-java-simple-publisher/pom.xml package
java -jar samples/pure-java-simple-publisher/target/pure-java-simple-publisher-0.1.0.jar
```

Back in the subscriber terminal you should see:

```
[my-subscriber] demo/hello -> hello from another terminal
```

Ctrl-C stops the subscriber cleanly.

### Override the defaults

All three positional args at once:

```bash
# Path A
java -jar target/my-zenoh-subscriber-0.1.0-fat.jar tcp/router.local:7447 sensors/** 30
```

That connects to a router on `router.local`, subscribes to every
key under `sensors/`, and exits after 30 seconds.

On **Windows / PowerShell**, use forward-slash paths for the jar or
escape the backslashes, and forget about bash `\` line
continuations — put the whole command on one line:

```powershell
java -jar target\my-zenoh-subscriber-0.1.0-fat.jar tcp/router.local:7447 sensors/** 30
```

---

## Step 5. Where to go next

* **TLS / mTLS** — flip your endpoint to `tls/...` or `wss/...` and
  add `.rootCaCertPath(...)`, `.clientCertPath(...)`,
  `.clientKeyPath(...)` on the builder. PEM and PKCS12 are both
  accepted; the file extension picks the loader. Full worked
  example: [`../samples/pure-java-mtls-subscriber/`](../samples/pure-java-mtls-subscriber/).
* **Publisher** — the API mirrors this exactly. Swap
  `PureJavaZenohSubscriber` for
  [`PureJavaZenohPublisher`](src/main/java/io/mdudel/zenoh/purejava/PureJavaZenohPublisher.java)
  and call `.publish(bytes)` instead of `.subscribeAndConsume(...)`.
  Worked example: [`../samples/pure-java-simple-publisher/`](../samples/pure-java-simple-publisher/).
* **Scouting** — discover routers/peers on the local segment
  without ever opening a session:
  [`PureJavaZenohScout`](src/main/java/io/mdudel/zenoh/purejava/scouting/PureJavaZenohScout.java)
  + sample [`../samples/pure-java-scout/`](../samples/pure-java-scout/).
* **Pull-style consumption** — call `sub.subscribe(keyExpr)` and
  `subscription.take()` / `.poll(timeout, unit)` on the returned
  handle instead of using `subscribeAndConsume`. Gives you full
  control over which thread processes samples.
* **Bridging logs to SLF4J / Log4j / Logback** — see
  [pure-java README "Bridging logs"](README.md#bridging-logs).

---

## Troubleshooting cheat-sheet

| Symptom | Likely cause | Fix |
|---|---|---|
| `no main manifest attribute, in target/my-zenoh-subscriber-0.1.0.jar` | On Path A you ran `java -jar` against the thin non-fat jar. | Use `target/my-zenoh-subscriber-0.1.0-fat.jar` (the shaded one with `-fat` in the filename). |
| `cannot find symbol: class PureJavaZenohSubscriber` at compile time | Path A `~/.m2` doesn't have the pure-java jar, or it's stale after a `git pull` of the upstream repo. | `mvn -f pure-java/pom.xml install` from the upstream clone, then `mvn clean package` in your project. |
| `java.net.ConnectException: Connection refused` at `start()` | Router isn't running, or endpoint host/port is wrong. | Start `zenohd` / the Docker container, or fix the endpoint. |
| Subscriber sits at `session OPEN` and nothing arrives | The publisher's key doesn't match your subscription's key expression. | Widen the subscription (e.g. `**` to catch everything), or check the publisher's key with `z_sub -k '**'` alongside. |
| `IOException` with `TLS handshake failed` | Router requires mTLS and you didn't provide a client cert + key, or your cert isn't signed by a CA the router trusts. | Add `.clientCertPath(...)` + `.clientKeyPath(...)` + `.rootCaCertPath(...)`, or see the mTLS sample. |
| `UnsupportedOperationException` about UDP or QUIC | UDP session transport and QUIC are out-of-scope in the pure-java module. | Use `tcp/`, `tls/`, `ws/`, or `wss/`. QUIC would need a third-party dep like Kwik — see the [Non-goals](README.md#non-goals-permanent-not-yet) section. |
