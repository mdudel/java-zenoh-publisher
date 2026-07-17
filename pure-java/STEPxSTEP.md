# STEPxSTEP: build a Zenoh subscriber with the pure-Java client

A hands-on walkthrough, from starting with an empty directory, to
a running subscriber jar. It uses the "copy the sources into your
own project" approach so that you end up with a completely
self-contained build that has no third-party runtime dependencies
of any kind beyond the JDK, which is usually what you want if
you're reading this file.

By the end you will have a small Maven project called
`my-zenoh-subscriber` containing a single class `MySubscriber` that
opens a session to a Zenoh router, subscribes to a key expression,
and prints one line per received message. You'll also have a
runnable jar you can hand to a colleague and expect them to be
able to run with nothing more than a JDK 17 install.

The only things you need on your machine before starting are a
JDK 17 or newer (check with `java -version` and `javac -version`),
Maven 3.6 or newer (check with `mvn -v`), and a Zenoh router
somewhere reachable. If you don't already have a router, the
easiest way to get one is `docker run --rm -it -p 7447:7447
eclipse/zenoh`, which will bind the default Zenoh port on
`localhost` and give you something to publish and subscribe
against; alternatively, download a native `zenohd` binary from
the [Zenoh releases page](https://github.com/eclipse-zenoh/zenoh/releases)
and run it directly.

---

## Step 1. Create an empty Maven project

Pick a directory anywhere on disk and lay out the standard Maven
skeleton:

```bash
mkdir -p my-zenoh-subscriber/src/main/java/com/example
cd my-zenoh-subscriber
```

Create a `pom.xml` at the project root. Notice that there is no
`<dependencies>` block at all: the pure-Java Zenoh client has zero
third-party runtime dependencies, and we're about to copy its
sources directly into `src/main/java`, so the JDK 17 standard
library really is the only thing this project needs to compile
and run.

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

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.13.0</version>
      </plugin>

      <!-- Standard maven-jar-plugin, with Main-Class set on the
           manifest so `java -jar target/my-zenoh-subscriber-0.1.0.jar`
           will find MySubscriber without any extra classpath fuss.
           No shade plugin is needed here, because everything that
           ends up in target/classes is already ours: the pure-Java
           sources are copied in during Step 2 and there is nothing
           external to bundle. -->
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

---

## Step 2. Copy the pure-Java client sources into your project

Clone the `java-zenoh-publisher` repository somewhere convenient
(a scratch directory like `/tmp` is fine, it doesn't need to live
next to your project on disk), then copy the entire
`io/mdudel/zenoh/purejava/...` source tree straight into your own
project's `src/main/java`:

```bash
# From inside your my-zenoh-subscriber directory:
git clone https://github.com/mdudel/java-zenoh-publisher.git /tmp/jzp
cp -r /tmp/jzp/pure-java/src/main/java/io src/main/java/
```

You should now have `src/main/java/io/mdudel/zenoh/purejava/...`
sitting alongside `src/main/java/com/example/` in your project.
The pure-Java module is written to only depend on the JDK
standard library, so nothing else needs to come with it — no
transitive jars, no vendored binaries, no Kotlin stdlib, nothing.

Because the sources now live under your own `src/main/java`, they
compile as part of your normal Maven build, which means anything
your accreditation or code-review process applies to your own
code (static analysis, licence scanning, SBOM generation, etc.)
will apply to the Zenoh client sources at the same time. That is
usually the whole point of taking the sources-in approach rather
than pulling a jar off a repository.

While you're there, it's polite to preserve the Apache 2.0
licence header on the pure-Java sources by dropping a copy of the
upstream `LICENSE` file at your project root:

```bash
cp /tmp/jzp/pure-java/LICENSE LICENSE
```

If you later need to pick up upstream changes, the mechanical
answer is to delete `src/main/java/io/mdudel/zenoh/purejava` and
re-run the `cp -r` above from a freshly-pulled clone. There is no
version negotiation, no dependency resolution and no `~/.m2`
cache to keep in sync — it is a plain source copy, and if you
modify the copy locally, those modifications stay yours.

---

## Step 3. Write the subscriber main class

Create `src/main/java/com/example/MySubscriber.java` with the
following contents. The class opens a session, subscribes to a
key expression, prints one line per received message, and closes
cleanly when either Ctrl-C is pressed or an optional timeout
elapses.

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
 *   java -jar target/my-zenoh-subscriber-0.1.0.jar
 *     [endpoint]         default: tcp/localhost:7447
 *     [keyExpr]          default: demo/**
 *     [timeoutSeconds]   default: 0 (run until Ctrl-C)
 * </pre>
 */
public final class MySubscriber {

    public static void main(String[] args) throws Exception {
        String endpoint       = args.length > 0 ? args[0] : "tcp/localhost:7447";
        String keyExpr        = args.length > 1 ? args[1] : "demo/**";
        long   timeoutSeconds = args.length > 2 ? Long.parseLong(args[2]) : 0L;

        System.out.println("[my-subscriber] connecting to " + endpoint
                + " subscribing to " + keyExpr
                + (timeoutSeconds > 0 ? " for " + timeoutSeconds + "s"
                                      : " (Ctrl-C to stop)"));

        // A latch that a shutdown hook flips on SIGINT, so that
        // Ctrl-C tears the session down cleanly instead of leaving
        // a half-open socket on the router side.
        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(stop::countDown));

        // try-with-resources guarantees close() runs even on
        // exceptions, which sends a Zenoh CLOSE frame to the router
        // and joins the internal reader thread before returning.
        try (PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint(endpoint)
                .build()) {

            // start() opens the TCP/TLS/WS transport and completes
            // the Zenoh 4-message handshake. It blocks until the
            // session is OPEN, or throws IOException on failure.
            sub.start();
            System.out.println("[my-subscriber] session OPEN");

            // Push-style subscribe: the lambda runs on a fresh
            // daemon thread inside the subscriber every time a
            // sample matching the key expression arrives. If you'd
            // prefer to control the receiving thread yourself,
            // call sub.subscribe(keyExpr) instead and pull samples
            // via subscription.take() or .poll(timeout, unit) from
            // a thread you own.
            sub.subscribeAndConsume(keyExpr, sample -> {
                System.out.println("[my-subscriber] "
                        + sample.key() + " -> " + sample.payloadAsString());
            });

            // Block until either the caller-supplied timeout
            // elapses or the shutdown hook fires.
            if (timeoutSeconds > 0) {
                stop.await(timeoutSeconds, TimeUnit.SECONDS);
            } else {
                stop.await();
            }

            System.out.println("[my-subscriber] shutting down"
                    + " (received=" + sub.getReceivedCount() + ")");
        }
    }
}
```

A few notes on the API this main class touches, in case you want
to adapt it later:

* `builder().connectEndpoint(...)` accepts any of `tcp/host:port`,
  `tls/host:port`, `ws/host:port` or `wss/host:port`. For TLS or
  mTLS you'd also chain `.rootCaCertPath(...)`,
  `.clientCertPath(...)` and `.clientKeyPath(...)` on the builder;
  the pure-Java module accepts both PEM and PKCS12 material and
  picks the right loader based on the file extension. The
  [`pure-java-mtls-subscriber` sample](../samples/pure-java-mtls-subscriber/)
  in the upstream repo shows the mTLS builder in full.
* `sample.key()` returns the concrete key the sample was
  published under, not the wildcard subscription pattern. So a
  subscription on `demo/**` receiving a sample published under
  `demo/room1/temp` will see `sample.key() == "demo/room1/temp"`.
* `sample.payloadAsString()` is a UTF-8 convenience accessor; use
  `sample.payload()` if you want the raw bytes (it returns a
  defensive copy, so you're free to mutate the array without
  disturbing anything).
* `close()` (either called explicitly or triggered by the
  try-with-resources block) is idempotent and safe to call from
  any thread.

If you want to subscribe to something other than the default
`demo/**` wildcard, the key-expression syntax the subscriber
understands matches Zenoh's standard grammar. `sensors/room1/temp`
matches exactly that one key. `sensors/*/temp` uses the
single-chunk wildcard: it will match `sensors/room1/temp` and
`sensors/kitchen/temp` but not `sensors/room1/upstairs/temp`.
`sensors/**` uses the double-star wildcard and matches
`sensors`, `sensors/room1`, `sensors/room1/temp` and any deeper
nesting. A bare `**` on its own catches everything flowing
through the router, which is useful when you're trying to work
out what your publishers are actually emitting. There is also a
sub-chunk wildcard: `log/$*Error` matches `log/Error`,
`log/FatalError` and `log/SoftError`.

---

## Step 4. Build the project

From the `my-zenoh-subscriber` directory:

```bash
mvn -q clean package
```

Look inside `target/` and you should find
`my-zenoh-subscriber-0.1.0.jar`, weighing in at around 60 KB.
That includes both your `MySubscriber` class and the entire
pure-Java Zenoh client, because both compiled from the same
`src/main/java` tree. There is no separate library jar to
distribute alongside it, and no fat / thin distinction to worry
about — this is the artifact.

If the compile fails with `cannot find symbol: class
PureJavaZenohSubscriber` (or any of its supporting classes), it
almost always means the `cp -r` from Step 2 didn't land the
sources where the compiler expects them. Double-check that
`src/main/java/io/mdudel/zenoh/purejava/PureJavaZenohSubscriber.java`
really exists at that exact path, and rerun `mvn -q clean
package`.

---

## Step 5. Run it

Start a Zenoh router if you haven't already. The simplest option
is Docker:

```bash
docker run --rm -it -p 7447:7447 eclipse/zenoh
```

Then, in a fresh terminal:

```bash
java -jar target/my-zenoh-subscriber-0.1.0.jar
```

You should see:

```
[my-subscriber] connecting to tcp/localhost:7447 subscribing to demo/** (Ctrl-C to stop)
[my-subscriber] session OPEN
```

At which point the subscriber is idling, waiting for anything
matching `demo/**` to be published. To prove the subscriber
actually receives data, you need to publish something to a
matching key. From another terminal any Zenoh publisher will do
— the Rust CLI's `z_put -k demo/hello -v 'hello from another
terminal'`, or a one-line Python `python -c "import zenoh;
s=zenoh.open(); s.put('demo/hello','hi'); s.close()"`, or the
pure-Java publisher sample bundled with the upstream repo:

```bash
cd /tmp/jzp
mvn -q -f pure-java/pom.xml install
mvn -q -f samples/pure-java-simple-publisher/pom.xml package
java -jar samples/pure-java-simple-publisher/target/pure-java-simple-publisher-0.1.0.jar
```

Back in the subscriber terminal you should see one line per
message, of the form:

```
[my-subscriber] demo/hello -> hello from another terminal
```

Ctrl-C stops the subscriber cleanly, and you should see the
`shutting down (received=N)` line print on the way out with the
count of samples the session actually processed.

The two extra positional arguments let you point the subscriber
at a remote router and narrow the subscription in one go, e.g.

```bash
java -jar target/my-zenoh-subscriber-0.1.0.jar tcp/router.local:7447 sensors/** 30
```

which connects to `router.local:7447`, subscribes to everything
under `sensors/`, and exits after 30 seconds. On Windows /
PowerShell put the whole command on one line and use either
forward-slash or escaped-backslash paths for the jar; the bash
`\` line-continuation trick does not work in PowerShell and will
just get eaten as a literal path separator:

```powershell
java -jar target\my-zenoh-subscriber-0.1.0.jar tcp/router.local:7447 sensors/** 30
```

---

## Where to go from here

Once the subscriber is running, the most likely next things
you'll want to do are all straightforward extensions of the same
shape:

* **Add TLS or mTLS.** Change your endpoint to `tls/host:port` or
  `wss/host:port` and chain the relevant cert / key /
  trust-anchor paths on the builder. Both PEM and PKCS12 are
  accepted. The [`pure-java-mtls-subscriber` sample](../samples/pure-java-mtls-subscriber/)
  works end-to-end against production `zenohd` under mTLS and is
  the shortest reference for the builder shape.
* **Publish as well as subscribe.** The API is deliberately
  mirrored between the two facades, so swap
  `PureJavaZenohSubscriber` for
  [`PureJavaZenohPublisher`](src/main/java/io/mdudel/zenoh/purejava/PureJavaZenohPublisher.java)
  and call `.publish(byte[])` instead of `.subscribeAndConsume(...)`.
  The [`pure-java-simple-publisher` sample](../samples/pure-java-simple-publisher/)
  is essentially the publisher-side twin of the class you just
  wrote.
* **Discover routers on the local segment.** If you want to find
  routers or peers without ever opening a session,
  [`PureJavaZenohScout`](src/main/java/io/mdudel/zenoh/purejava/scouting/PureJavaZenohScout.java)
  is a passive/active UDP-multicast listener with both a
  callback and a snapshot API. The
  [`pure-java-scout` sample](../samples/pure-java-scout/) wraps
  it into a live table.
* **Pull-style consumption instead of push.** Call
  `sub.subscribe(keyExpr)` and then `subscription.take()` (blocks)
  or `subscription.poll(timeout, unit)` (returns `null` on
  timeout). Useful when you want the receiving thread under
  your own control.
* **Route the client's logs into your existing logging stack.**
  The pure-Java module writes to `java.lang.System.Logger`, so
  adding SLF4J's `slf4j-jdk-platform-logging` bridge (plus
  whichever SLF4J backend you already use) is enough to have
  Zenoh log lines flow into your normal Logback / Log4j / etc.
  configuration. The full recipe is in the ["Bridging logs"
  section of the pure-java README](README.md#bridging-logs).

---

## Troubleshooting cheat-sheet

| Symptom | Likely cause | Fix |
|---|---|---|
| `cannot find symbol: class PureJavaZenohSubscriber` at compile time | The `cp -r` in Step 2 didn't land the sources under `src/main/java/io/mdudel/zenoh/purejava/...`. | Verify the path exists and re-run the copy from a fresh upstream clone. |
| `java.net.ConnectException: Connection refused` at `start()` | The router isn't running, or the endpoint host / port doesn't match reality. | Start `zenohd` (or the Docker container), or fix the endpoint. |
| Subscriber logs `session OPEN` and then nothing arrives | Your publisher's key doesn't match your subscription's key expression. | Widen the subscription temporarily (e.g. `**` to catch everything), or double-check the publisher's key. |
| `IOException` with `TLS handshake failed` | The router expects mTLS and you haven't supplied a client cert + key, or the cert isn't signed by a CA the router trusts. | Chain `.rootCaCertPath(...)`, `.clientCertPath(...)` and `.clientKeyPath(...)` on the builder, or consult the mTLS sample. |
| `UnsupportedOperationException` referencing UDP or QUIC | UDP session transport and QUIC are deliberately out of scope in the pure-Java module. | Use `tcp/`, `tls/`, `ws/` or `wss/`. QUIC would require a third-party dependency such as Kwik — see the [Non-goals](README.md#non-goals-permanent-not-yet) section of the pure-java README for the rationale. |
