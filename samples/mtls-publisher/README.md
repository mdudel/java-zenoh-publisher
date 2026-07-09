# mtls-publisher

Publisher over TLS with mutual authentication. Presents a client
certificate to the router; mTLS is enabled automatically when both
`--client-cert` and `--client-key` are supplied.

Note the two common footguns (both surface as clear warnings from
`ZenohClient.start()`):

- Cert paths on a **plain** endpoint (`tcp/`, `udp/`, `ws/`) are
  silently ignored - only `tls/`, `quic/`, and `wss/` trigger TLS.
- Most TLS Zenoh routers require mTLS. Pointing at `tls/` without
  a client cert + key will close the handshake at the far end with
  no useful diagnostic in the router log.

## Build

```bash
cd samples/mtls-publisher
mvn package
```

The build resolves the starter kit directly from the vendored
`vendor/repo/` alongside the other Zenoh dependencies - no
top-level install step required.

## Run

```bash
java -jar target/mtls-publisher-0.1.0.jar \
      tls/router.example.com:7447 my/key \
      /etc/pki/ca.pem /etc/pki/client.pem /etc/pki/client.key \
      true                        # optional: verify hostname (default false)
```

Positional args:

1. `endpoint`     - must be `tls/`, `quic/`, or `wss/`
2. `keyExpr`      - key to publish under
3. `rootCa`       - trust anchor PEM for the router cert
4. `clientCert`   - your client certificate PEM
5. `clientKey`    - your client private key PEM
6. `verifyHostname` - optional, `true`/`false` (default `false`)

Missing args print a usage line and exit with code 2.
