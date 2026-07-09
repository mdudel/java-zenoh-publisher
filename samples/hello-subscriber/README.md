# hello-subscriber

Minimal Zenoh subscriber. Prints every payload it receives on the
subscribed key expression to stdout, until Ctrl-C.

Deliberately does **not** use `ZenohClient`. That class is
publisher-only; this sample exists to show the ~60-line pattern
you would follow to build your own subscriber on top of the same
`io.zenoh.Session` / `io.zenoh.Config` primitives. The session
open + config code is identical to `ZenohClient.start()`; only the
`session.declarePublisher(...)` call is swapped for
`session.declareSubscriber(..., Callback)`.

## Build

```bash
cd samples/hello-subscriber
mvn package
```

The build resolves the starter kit directly from the vendored
`vendor/repo/` alongside the other Zenoh dependencies - no
top-level install step required.

## Run

```bash
# default: connect to tcp/localhost:7447, subscribe to demo/**
java -jar target/hello-subscriber-0.1.0.jar

# override endpoint and key expression:
java -jar target/hello-subscriber-0.1.0.jar tcp/router.local:7447 'my/**'
```

Args are positional: `<endpoint>` then `<keyExpr>`. Both optional.

Ctrl-C to stop.
