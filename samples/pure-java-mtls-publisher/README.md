# pure-java-mtls-publisher

Pure-Java Zenoh publisher over TLS with mutual authentication (mTLS).
Drop-in equivalent to the JNI [`../mtls-publisher/`](../mtls-publisher/)
sample: identical positional arguments, identical PEM cert format,
identical wire behaviour. The only difference is which class you
`new` &mdash; `PureJavaZenohPublisher` here vs `ZenohClient` there.

**Zero third-party runtime dependencies.** JDK 17 only. Under the
hood, uses our clean-room `PemLoader` + `TlsConfig.trustStorePem`
+ `keyStorePem` (see `../../pure-java/README.md`).

## Build

The pure-Java module lives at `../../pure-java/` in this repo.
One-time install into your local `~/.m2/repository`:

```bash
# From the repo root:
mvn -f pure-java/pom.xml install
```

Then this sample:

```bash
cd samples/pure-java-mtls-publisher
mvn package
```

Produces `target/pure-java-mtls-publisher-0.1.0.jar` &mdash; a
runnable fat jar around 90 KB (the entire pure-Java module is
under 50 KB and there's nothing else to shade in).

## Run

```powershell
# Full run (Windows PowerShell backtick line-continuation shown):
java -jar target\pure-java-mtls-publisher-0.1.0.jar `
    tls/localhost:7447 `
    demo/mtls/pure `
    D:\ZENOH\certs\ca.pem `
    D:\ZENOH\certs\client-cert.pem `
    D:\ZENOH\certs\client-key.pem `
    true
```

```bash
# Same, POSIX shells:
java -jar target/pure-java-mtls-publisher-0.1.0.jar \
    tls/localhost:7447 \
    demo/mtls/pure \
    /etc/pki/zenoh/ca.pem \
    /etc/pki/zenoh/client-cert.pem \
    /etc/pki/zenoh/client-key.pem \
    true
```

Positional args (all after the endpoint):

| # | Arg              | Notes |
|---|------------------|-------|
| 1 | `endpoint`       | `tls/host:port` or `wss/host:port` |
| 2 | `keyExpr`        | Zenoh key to publish under |
| 3 | `rootCa`         | Trust anchor PEM &mdash; validates the router cert |
| 4 | `clientCert`     | Your client cert PEM &mdash; presented to the router for mTLS |
| 5 | `clientKey`      | Your client private key PEM (PKCS#8 or PKCS#1, unencrypted) |
| 6 | `verifyHostname` | Optional. Default `true`. Set `false` for pinned-cert IP-only connects. |

## PEM or PKCS12? Both.

The `rootCa`, `clientCert`, and `clientKey` args are auto-detected
from the file extension:

- `.pem` / `.crt` / `.cer` / `.key` &rarr; PEM. Same shape as the
  JNI sibling &mdash; three separate files.
- `.p12` / `.pfx` &rarr; PKCS12. For PKCS12, pass the SAME file to
  both `clientCert` and `clientKey` (or leave `clientKey` blank);
  it's a combined keystore. The PKCS12 password defaults to
  `changeit`; override by writing a small wrapper program that
  uses the `PureJavaZenohPublisher.Builder.keyStorePassword(char[])`
  setter directly, since positional args here don't take a
  password to avoid shell-history leaks.

For a `keytool`-only PKCS12 &rarr; PEM conversion path, see
[`../../tools/Pkcs12ToPem.java`](../../tools/Pkcs12ToPem.java).

## Verify

The one line that PROVES mTLS worked is in `zenohd`'s DEBUG log:

```
Accepted TLS connection on [::1]:7447: [::1]:xxxxx. Common Name: client.
```

`Common Name: client` (or whatever CN you baked into your client
cert) &mdash; **not** `None` &mdash; means the router accepted a
TLS handshake in which the client presented a cert signed by the
trusted CA.

If instead the router log shows `Common Name: None`, the TLS
handshake succeeded but WITHOUT client-cert presentation. That's
usually the router's `zenohd-mtls.json5` missing `enable_mtls: true`
under `transport.link.tls`.

See `mtls-smoke-test.md` (in the workspace docs) for the full
router-side setup, cert generation with `keytool`, and end-to-end
smoke test.

## What's exercised

Every successful run touches:

- `PureJavaZenohPublisher` facade + endpoint parser (auto-picks
  `TlsTransport` for `tls/`, `WsTransport` for `wss/`)
- `TlsConfig.Builder.trustStorePem` and `keyStorePem` (our
  clean-room PEM loader; handles both PKCS#8 and PKCS#1 keys)
- `AbstractStreamTransport` reader thread + `TlsTransport` two-step
  connect (plain TCP then SSLSocket layer)
- `StreamFramer` 2-byte LE stream framing (identical to plain TCP)
- `ZenohSession` 4-message INIT/OPEN handshake carrying our
  ZenohId, negotiated over the mTLS-authenticated link
- Full wire codec: FRAME &rarr; PUSH &rarr; PUT

If any layer regresses, this sample stops delivering payloads
under mTLS.
