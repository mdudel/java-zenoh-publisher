# samples/

Copy-paste-ready examples that use the `ZenohClient` starter kit.
Each file is a single Java class with a `main()` — no build tool
required beyond having the fat jar on the classpath.

| Sample                      | What it shows                                              |
|-----------------------------|------------------------------------------------------------|
| `HelloPublisher.java`       | Absolute minimum: connect, publish one message, close.     |
| `HelloSubscriber.java`      | Companion subscriber (writes to `System.out`).             |
| `JsonPublisher.java`        | Structured JSON payloads + per-subkey publisher cache.     |
| `MtlsPublisher.java`        | TLS + mutual authentication (client cert + key).           |
| `CotStreamingPublisher.java`| Continuous streaming from a background thread. Configurable TTL, rate, and number of simulated tracks moving on elliptical paths. Emits small CoT XML events. |

## Prerequisites

- **JDK 17+** (`java -version`)
- The starter's fat jar. Build it once from the repo root:
  ```bash
  mvn -q clean package
  # → target/java-zenoh-publisher-0.1.0-fat.jar
  ```
- A **Zenoh router** for the samples to connect to. Easiest path:
  ```bash
  # docker (needs docker)
  docker run --rm -p 7447:7447 -p 8000:8000 eclipse/zenoh:1.9.0
  # or native binary from https://github.com/eclipse-zenoh/zenoh/releases
  ```

## Running a sample

Every sample compiles the same way. Example with `HelloPublisher`:

```bash
# from the repo root
mkdir -p /tmp/samples-out

javac -cp target/java-zenoh-publisher-0.1.0-fat.jar \
      samples/HelloPublisher.java \
      -d /tmp/samples-out

java  -cp target/java-zenoh-publisher-0.1.0-fat.jar:/tmp/samples-out \
      HelloPublisher
```

On Windows, use `;` instead of `:` as the classpath separator.

### End-to-end demo

Two shells:

```bash
# shell 1 — start a zenoh router
docker run --rm -p 7447:7447 eclipse/zenoh:1.9.0

# shell 2 — start the subscriber
javac -cp target/java-zenoh-publisher-0.1.0-fat.jar samples/HelloSubscriber.java -d /tmp/samples-out
java  -cp target/java-zenoh-publisher-0.1.0-fat.jar:/tmp/samples-out HelloSubscriber

# shell 3 — fire the publisher a couple of times
javac -cp target/java-zenoh-publisher-0.1.0-fat.jar samples/HelloPublisher.java -d /tmp/samples-out
java  -cp target/java-zenoh-publisher-0.1.0-fat.jar:/tmp/samples-out HelloPublisher
```

The subscriber should print:

```
[HelloSubscriber] listening key=demo/** via tcp/localhost:7447 (Ctrl-C to stop)
[HelloSubscriber] demo/greeting -> hello, zenoh from java!
```

## Args

All samples accept `endpoint` and `keyExpr` as optional positional args
(defaults `tcp/localhost:7447` and a sensible per-sample key). Read the
Javadoc at the top of each file for the full arg list.

## Why the samples don't use a build tool

Deliberate. The point is that once you have the fat jar, adding Zenoh
to a random Java project is just:

1. Put the fat jar on your classpath.
2. `import io.mdudel.zenoh.ZenohClient;`
3. Build a client and call `.start()` / `.publish()` / `.stop()`.

If you want a Maven / Gradle setup instead, the parent repo's `pom.xml`
is your reference — copy the `<dependency>`, `<repositories>`, and
(optionally) `<build><plugins>` blocks into your own project.
