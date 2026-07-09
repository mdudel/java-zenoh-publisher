# hello-publisher

Absolute minimum viable Zenoh publisher using the starter kit's
`ZenohClient`. Opens a session to a router, publishes one payload,
closes. About 20 lines of business logic.

## Build

```bash
# from the repo root, one-time install of the starter kit:
mvn -f pom.xml install

# then:
cd samples/hello-publisher
mvn package
```

## Run

```bash
# default: connect to tcp/localhost:7447, publish to demo/greeting
java -jar target/hello-publisher-0.1.0-fat.jar

# override endpoint and key:
java -jar target/hello-publisher-0.1.0-fat.jar tcp/router.local:7447 my/key
```

Args are positional: `<endpoint>` then `<keyExpr>`. Both optional.

## Verify

Run any Zenoh subscriber on the same key first (see
[`../hello-subscriber/`](../hello-subscriber/) or use
`z_sub -k 'demo/**'`). You should see:

```
[HelloSubscriber] demo/greeting -> hello, zenoh from java!
```
