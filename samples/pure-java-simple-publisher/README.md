# pure-java-simple-publisher

Absolute minimum viable pure-Java Zenoh publisher. Uses
`io.mdudel.zenoh.purejava.PureJavaZenohPublisher` to open a session
to a router, publish N payloads at a configurable interval, close.

**Zero third-party runtime dependencies.** JDK 17 only.

Sibling to [`hello-publisher/`](../hello-publisher/), which is the
JNI-backed equivalent. The two are drop-in swappable at the call
site: same builder shape, same start/publish/close lifecycle. The
only real difference at 20 LOC of business logic is which class you
`new`.

## Build

The pure-Java module lives at `../../pure-java/` in this repo.
One-time install into your local `~/.m2/repository`:

```bash
# From the repo root:
mvn -f pure-java/pom.xml install
```

> **After a `git pull`**: re-run the above `mvn install` before rebuilding
> this sample. Otherwise you'll get `cannot find symbol: class X` when
> the pure-Java module has grown a new public class since your last
> install — the .m2 jar is invisible and goes stale silently.

That drops `java-zenoh-publisher-pure-0.0.1-SNAPSHOT.jar` into
your local Maven cache, from which this sample resolves it. Then:

```bash
cd samples/pure-java-simple-publisher
mvn package
```

Produces `target/pure-java-simple-publisher-0.1.0.jar` — a
runnable fat jar around 60 KB (the pure-Java module is under
50 KB and there's nothing else to shade in).

> Why not vendor the pure-Java jar into `vendor/repo/` like the JNI
> starter? The JNI starter includes a 28 MB native binary that has
> to be committed for reproducible builds. The pure-Java module is
> tiny and rebuilds from source in under 5 seconds; a one-time
> `mvn install` is cheaper than the git-storage cost of yet another
> jar. Real deployments should replace the `<version>` with whatever
> your artifact-repo publishes.

## Run

```bash
# Defaults: tcp/localhost:7447, key=demo/greeting, 5 messages @ 1000ms
java -jar target/pure-java-simple-publisher-0.1.0.jar

# Override endpoint, key, count, interval:
java -jar target/pure-java-simple-publisher-0.1.0.jar \
    tcp/router.local:7447 my/key 10 500

# Over TLS:
java -jar target/pure-java-simple-publisher-0.1.0.jar tls/router:7449

# Over WebSocket (plaintext or wss):
java -jar target/pure-java-simple-publisher-0.1.0.jar ws/router:7447
java -jar target/pure-java-simple-publisher-0.1.0.jar wss/router:7447
```

Positional args (all optional): `<endpoint> <key> <count> <intervalMs>`.

## Verify

Run any Zenoh subscriber on the same key first (Rust `z_sub`,
Python `z_sub.py`, or the JNI sibling `hello-subscriber`):

```bash
z_sub -k 'demo/greeting'
```

You should see one line per published message, e.g.

```
>> [Subscriber] Received PUT ('demo/greeting': hello #1 from pure-Java)
>> [Subscriber] Received PUT ('demo/greeting': hello #2 from pure-Java)
...
```

## What's exercised

Every run touches every layer of the pure-Java module:

- `PureJavaZenohPublisher` facade + endpoint parser
- `TcpTransport` (or `TlsTransport` / `WsTransport` depending on
  scheme) with `AbstractStreamTransport` / `AbstractTransport`
  lifecycle machinery
- `StreamFramer` 2-byte little-endian length prefix
- `ZenohSession` 4-message INIT/OPEN handshake, KEEP_ALIVE
  scheduler, graceful CLOSE emission
- Wire codec: FRAME &rarr; PUSH &rarr; PUT with the string encoding
  tag

If any of those regress, this sample stops delivering payloads to
the subscriber side.
