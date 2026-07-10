# pure-java-simple-subscriber

Absolute minimum viable pure-Java Zenoh subscriber. Uses
`io.mdudel.zenoh.purejava.PureJavaZenohSubscriber` to open a session
to a router, subscribe to a key expression (with wildcard support),
print one line per received message.

**Zero third-party runtime dependencies.** JDK 17 only.

Sibling of [`pure-java-simple-publisher/`](../pure-java-simple-publisher/).
Run the two together against the same router (or against a real
`zenohd`) to see the whole pure-Java stack in action end to end.

## Build

The pure-Java module lives at `../../pure-java/` in this repo.
One-time install into your local `~/.m2/repository`:

```bash
# From the repo root:
mvn -f pure-java/pom.xml install
```

> **After a `git pull`**: re-run the above `mvn install` before rebuilding
> this sample. Otherwise you'll get
> `cannot find symbol: class PureJavaZenohSubscriber` (or similar) when
> the pure-Java module has grown a new public class since your last
> install — the .m2 jar is invisible and goes stale silently.

Then this sample:

```bash
cd samples/pure-java-simple-subscriber
mvn package
```

Produces `target/pure-java-simple-subscriber-0.1.0.jar` — a runnable
fat jar around 95 KB (the pure-Java module is under 60 KB and there's
nothing else to shade in).

## Run

```bash
# Defaults: tcp/localhost:7447, key=demo/**, run until Ctrl-C
java -jar target/pure-java-simple-subscriber-0.1.0.jar

# Override endpoint, key, timeout (0=forever):
java -jar target/pure-java-simple-subscriber-0.1.0.jar \
    tcp/router.local:7447 my/key 30

# Over TLS:
java -jar target/pure-java-simple-subscriber-0.1.0.jar tls/router:7449 my/**

# Over WebSocket:
java -jar target/pure-java-simple-subscriber-0.1.0.jar ws/router:7447 my/**
java -jar target/pure-java-simple-subscriber-0.1.0.jar wss/router:7447 my/**
```

Positional args (all optional):
`<endpoint>` `<keyExpr>` `<timeoutSeconds>`.

## Key expressions and wildcards

The subscriber understands the standard Zenoh key expression syntax:

- `sensors/room1/temp` — exact key match, one topic only
- `sensors/*/temp` — one-chunk wildcard, matches `sensors/room1/temp`
  and `sensors/kitchen/temp` but not `sensors/room1/x/temp`
- `sensors/**` — zero-or-more-chunks wildcard, matches
  `sensors`, `sensors/room1`, `sensors/room1/temp`, etc.
- `**` — everything flowing through the router
- `log/$*Error` — sub-chunk wildcard, matches `log/Error`,
  `log/FatalError`, `log/SoftError`

## Verify against the publisher sample

Two-terminal test:

```bash
# Terminal 1: start the subscriber first
java -jar samples/pure-java-simple-subscriber/target/pure-java-simple-subscriber-0.1.0.jar

# Terminal 2: publish five messages
java -jar samples/pure-java-simple-publisher/target/pure-java-simple-publisher-0.1.0.jar
```

You should see five lines appear in Terminal 1:

```
[pure-java-simple-subscriber] demo/greeting -> hello #1 from pure-Java
[pure-java-simple-subscriber] demo/greeting -> hello #2 from pure-Java
[pure-java-simple-subscriber] demo/greeting -> hello #3 from pure-Java
[pure-java-simple-subscriber] demo/greeting -> hello #4 from pure-Java
[pure-java-simple-subscriber] demo/greeting -> hello #5 from pure-Java
```

Requires a running Zenoh router (either the JNI one, or a real
`zenohd`, or the `mtls-smoke-test.md` walkthrough's setup) to route
messages between them.

## What's exercised

Every successful run touches:

- `PureJavaZenohSubscriber` facade + endpoint parser (auto-picks
  `TcpTransport` for `tcp/`, `TlsTransport` for `tls/`, `WsTransport`
  for `ws/`/`wss/`)
- `TlsConfig` (for TLS variants, with the same PEM + PKCS12
  auto-detection as the publisher facade)
- `AbstractStreamTransport` / `AbstractTransport` lifecycle machinery
- `StreamFramer` 2-byte LE stream framing on inbound messages
- `ZenohSession` 4-message INIT/OPEN handshake, KEEP_ALIVE scheduler,
  DECLARE outbound + inbound routing
- `Subscription` blocking queue + callback wrapper
- Wire codec: FRAME &rarr; PUSH &rarr; PUT decode, plus
  DECLARE/DeclareSubscriber encode
- `KeyExpr.matches(...)` for router-side wildcard routing

If any layer regresses, this sample stops delivering.

## Programmatic API

If you'd rather embed instead of run the jar directly:

```java
try (var sub = PureJavaZenohSubscriber.builder()
        .connectEndpoint("tcp/router:7447")
        .build()) {

    sub.start();

    // Callback style
    var subscription = sub.subscribeAndConsume("demo/**", sample ->
        System.out.println(sample.key() + " -> " + sample.payloadAsString()));

    // ... or pull style
    var pull = sub.subscribe("other/**");
    while (pull.isOpen()) {
        var s = pull.poll(1, TimeUnit.SECONDS);
        if (s != null) doSomething(s);
    }
}
```

Both styles use the same underlying `Subscription`; the callback
version spins a daemon thread that calls `take()` in a loop.
