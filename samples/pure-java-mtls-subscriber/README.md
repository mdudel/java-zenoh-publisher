# pure-java-mtls-subscriber

Pure-Java Zenoh subscriber over TLS with mutual authentication (mTLS).
Drop-in sibling of [`../pure-java-mtls-publisher/`](../pure-java-mtls-publisher/)
— same positional-arg contract, same PEM cert format, same wire
behaviour. The only difference is which class runs behind `main()`
(`PureJavaZenohSubscriber` here vs `PureJavaZenohPublisher` there).

**Zero third-party runtime dependencies.** JDK 17 only. Same clean-
room `PemLoader` + `TlsConfig` + `ZenohSession` + `Subscription`
plumbing you already validated in the publisher smoke test.

## Build

One-time install of the pure-Java module (skip if you already did it
for the publisher):

```bash
mvn -f pure-java/pom.xml install
```

Then this sample:

```bash
cd samples/pure-java-mtls-subscriber
mvn package
```

Produces `target/pure-java-mtls-subscriber-0.1.0.jar` — a runnable
fat jar around 125 KB.

## Run

```powershell
# Windows PowerShell (backtick line-continuation)
java -jar target\pure-java-mtls-subscriber-0.1.0.jar `
    tls/localhost:7447 `
    demo/mtls/pure `
    D:\ZENOH\certs\ca.pem `
    D:\ZENOH\certs\client-cert.pem `
    D:\ZENOH\certs\client-key.pem `
    true
```

Prints one line per received message. Runs until Ctrl-C, or until an
optional 7th positional arg (`timeoutSeconds`) elapses.

Positional args (same as `pure-java-mtls-publisher` positions 1-6,
plus one extra):

| # | Arg              | Notes |
|---|------------------|-------|
| 1 | `endpoint`       | `tls/host:port` or `wss/host:port` |
| 2 | `keyExpr`        | Zenoh KE to subscribe to (wildcards OK) |
| 3 | `rootCa`         | Trust anchor PEM (or `.p12`) |
| 4 | `clientCert`     | Your client cert PEM (or `.p12`) |
| 5 | `clientKey`      | Your client key PEM (PKCS#8 or PKCS#1) |
| 6 | `verifyHostname` | Optional, default `true` |
| 7 | `timeoutSeconds` | Optional, default `0` (run forever) |

## Verify against the publisher

Two-terminal test:

```powershell
# Terminal 1: subscriber first
java -jar samples/pure-java-mtls-subscriber/target/pure-java-mtls-subscriber-0.1.0.jar `
    tls/localhost:7447 demo/** `
    D:\ZENOH\certs\ca.pem D:\ZENOH\certs\client-cert.pem D:\ZENOH\certs\client-key.pem true

# Terminal 2: publisher
java -jar samples/pure-java-mtls-publisher/target/pure-java-mtls-publisher-0.1.0.jar `
    tls/localhost:7447 demo/mtls/pure `
    D:\ZENOH\certs\ca.pem D:\ZENOH\certs\client-cert.pem D:\ZENOH\certs\client-key.pem true
```

You should see one line in Terminal 1:

```
[PureMtlsSubscriber] demo/mtls/pure -> secure hello from pure-Java
```

## Verify mTLS happened (not just server-only TLS)

Router-side, look for BOTH:

```
Accepted TLS connection on 127.0.0.1:7447: 127.0.0.1:xxxxx. Common Name: client.  (publisher session)
Accepted TLS connection on 127.0.0.1:7447: 127.0.0.1:yyyyy. Common Name: client.  (subscriber session)
```

Two distinct client sessions, both authenticated with the same
CA-signed cert, both showing `Common Name: client` — that's byte-
level mTLS interop for both directions of the pure-Java module.

See [`../../docs/mtls-smoke-test.md`](../../docs/mtls-smoke-test.md)
for the full walkthrough including router config and cert generation.

## What's exercised

Every successful run touches every layer of the pure-Java module on
the subscriber side:

- `PureJavaZenohSubscriber` facade + endpoint parser
- `TlsConfig` PEM/PKCS12 auto-detection
- `TlsTransport` two-step handshake
- `StreamFramer` on the encrypted stream
- `ZenohSession` 4-message INIT/OPEN + DECLARE outbound + inbound routing
- `Subscription` blocking queue + `forEach` callback wrapper
- Wire codec: DECLARE + DeclareSubscriber encode, FRAME → PUSH → PUT decode
- `KeyExpr.matches` for router-side wildcard routing
