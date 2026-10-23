# Pure-Java Zenoh Client — TLS & mTLS Smoke Test Guide

This guide explains how to smoke test the pure-Java Zenoh client (`io.mdudel:java-zenoh-publisher-pure`) against a TLS-enabled Zenoh router (`zenohd`) using your PEM files.

---

## 1. Inspect Your 3 PEM Files First (Important!)

You mentioned having **the CA chain, the cert, and the public key**. Before running the test, verify the PEM headers (`-----BEGIN ...-----`) inside your three files, because **TLS / mTLS** and **Zenoh RSA Public-Key Authentication** use different key types:

```powershell
# PowerShell: inspect the BEGIN/END headers of your PEM files
Select-String -Pattern "-----BEGIN" ca-chain.pem, cert.pem, key.pem
```

```bash
# Linux / macOS:
grep "-----BEGIN" ca-chain.pem cert.pem key.pem
```

### Which scenario matches your files?

| Scenario | File 1 | File 2 | File 3 | What It Does |
| :--- | :--- | :--- | :--- | :--- |
| **Scenario A: Mutual TLS (mTLS)** *(Most Common)* | **CA Chain** (`-----BEGIN CERTIFICATE-----`, 1 or more certs) | **Client Cert** (`-----BEGIN CERTIFICATE-----`) | **Client Private Key** (`-----BEGIN PRIVATE KEY-----`, `RSA PRIVATE KEY`, or `EC PRIVATE KEY`) | Router verifies client identity via TLS client certificate; client verifies router via CA chain. *(Note: the client's public key is already embedded inside `cert.pem`; mTLS requires the **private key** to sign the TLS `CertificateVerify` handshake.)* |
| **Scenario B: One-Way Server TLS** (`verify_client: false` on router) | **CA Chain** (`-----BEGIN CERTIFICATE-----`) | **Server Cert** *(optional, for cert pinning)* | *(Not needed for TLS handshake)* | Client verifies the router's TLS certificate against the CA chain (or pins the server cert directly). No client private key is required. |
| **Scenario C: TLS + Zenoh RSA Public-Key Auth** (`transport.auth.pubkey`) | **CA Chain** (`-----BEGIN CERTIFICATE-----`) | **Client Cert** (or Server Cert) | **RSA Public Key** (`-----BEGIN PUBLIC KEY-----`) + **RSA Private Key** | Uses TLS for transport encryption and Zenoh's wire-level `InitSyn`/`OpenSyn` RSA challenge-response (`ZenohRsaAuthenticator`). Still requires the client's private key to decrypt the router's nonce challenge. |

> [!WARNING]
> **If your 3rd file starts with `-----BEGIN PUBLIC KEY-----` and you do NOT have a `-----BEGIN ... PRIVATE KEY-----` file:**
> - You **can** run **Scenario B (One-Way Server TLS)** right now using just the **CA chain** (or pinning the server's `cert.pem`), provided the router does not require client certificate authentication (`tls.verify_client: false`).
> - You **cannot** complete a **Mutual TLS (mTLS)** client handshake without the client's **private key**, because TLS 1.2/1.3 requires the client to cryptographically sign the handshake transcript using the private key matching `cert.pem`.

---

## 2. Build the Pure-Java Client & Smoke-Test Samples

From the repository root (`java-zenoh-publisher`), build and install `pure-java` into your local Maven repository, then package the standalone CLI sample JARs:

```bash
# 1. Build and install pure-java (includes RSA + EC P-256/P-384/P-521 PEM support and auto-reconnect)
mvn -f pure-java/pom.xml clean install

# 2. Build the runnable mTLS subscriber and publisher fat JARs
mvn -f samples/pure-java-mtls-subscriber/pom.xml clean package
mvn -f samples/pure-java-mtls-publisher/pom.xml clean package
```

---

## 3. Smoke Test Scenarios

### Scenario A: Mutual TLS (mTLS) — CA Chain + Client Cert + Client Private Key

Use this when `zenohd` is configured with `tls.root_ca_certificate`, `tls.listen_certificate`, `tls.listen_private_key`, and `tls.verify_client: true` (mTLS).

#### Step A1: Start the Subscriber (Terminal 1)
```bash
java -jar samples/pure-java-mtls-subscriber/target/pure-java-mtls-subscriber-0.1.0-SNAPSHOT.jar \
  --endpoint tls/<router-host-or-ip>:7447 \
  --key "demo/tls/smoke/**" \
  --ca /path/to/ca-chain.pem \
  --cert /path/to/client-cert.pem \
  --key-file /path/to/client-key.pem
```

*(Tip: If you are connecting by IP address or `localhost` and the router certificate's SAN/CN does not match the hostname, append `--no-verify-hostname` during smoke testing.)*

#### Step A2: Publish Smoke-Test Messages (Terminal 2)
```bash
java -jar samples/pure-java-mtls-publisher/target/pure-java-mtls-publisher-0.1.0-SNAPSHOT.jar \
  --endpoint tls/<router-host-or-ip>:7447 \
  --key "demo/tls/smoke/hello" \
  --ca /path/to/ca-chain.pem \
  --cert /path/to/client-cert.pem \
  --key-file /path/to/client-key.pem \
  --count 5 \
  --interval-ms 1000 \
  --payload-prefix "tls-smoke"
```

#### Expected Output
- **Publisher terminal:**
  ```text
  Connected. Publishing 5 message(s) every 1000 ms ...
  [1/5] PUT demo/tls/smoke/hello -> tls-smoke #1 @ 2026-...
  ...
  Done. Closing publisher.
  ```
- **Subscriber terminal:**
  ```text
  Connected. Waiting for samples on 'demo/tls/smoke/**' (Ctrl+C to exit) ...
  [2026-...] #1 demo/tls/smoke/hello -> tls-smoke #1 @ 2026-...
  ```

---

### Scenario B: One-Way Server TLS (CA Chain Only, or Pinned Server Certificate)

Use this when the router presents a TLS certificate signed by your CA chain (or a self-signed server certificate) and does **not** require client certificates (`verify_client: false`), or when you only have the CA chain and server cert/public key.

#### Option B1: Using the CLI Samples (CA Chain Only)
Both CLI sample JARs support one-way TLS simply by omitting `--cert` and `--key-file`:

```bash
# Terminal 1 — Subscriber with CA chain only
java -jar samples/pure-java-mtls-subscriber/target/pure-java-mtls-subscriber-0.1.0-SNAPSHOT.jar \
  --endpoint tls/<router-host-or-ip>:7447 \
  --key "demo/tls/smoke/**" \
  --ca /path/to/ca-chain.pem

# Terminal 2 — Publisher with CA chain only
java -jar samples/pure-java-mtls-publisher/target/pure-java-mtls-publisher-0.1.0-SNAPSHOT.jar \
  --endpoint tls/<router-host-or-ip>:7447 \
  --key "demo/tls/smoke/hello" \
  --ca /path/to/ca-chain.pem \
  --count 5
```

#### Option B2: Programmatic Java API (CA Chain or Pinned Server Certificate)

With [`PureJavaZenohPublisher`](pure-java/src/main/java/io/mdudel/zenoh/purejava/PureJavaZenohPublisher.java) and [`PureJavaZenohSubscriber`](pure-java/src/main/java/io/mdudel/zenoh/purejava/PureJavaZenohSubscriber.java):

```java
import io.mdudel.zenoh.purejava.PureJavaZenohPublisher;
import io.mdudel.zenoh.purejava.PureJavaZenohSubscriber;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class OneWayTlsSmokeTest {
    public static void main(String[] args) throws Exception {
        String endpoint = "tls/127.0.0.1:7447";
        Path caChain = Path.of("/path/to/ca-chain.pem");

        PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint(endpoint)
                .keyExpr("demo/tls/smoke/**")
                .rootCaCertPath(caChain)
                .verifyHostname(false) // set true in production when SAN matches hostname
                .listener((key, payload) ->
                        System.out.println("RECV [" + key + "]: " + new String(payload, StandardCharsets.UTF_8)))
                .build();

        PureJavaZenohPublisher pub = PureJavaZenohPublisher.builder()
                .connectEndpoint(endpoint)
                .keyExpr("demo/tls/smoke/hello")
                .rootCaCertPath(caChain)
                .verifyHostname(false)
                .build();

        sub.connect();
        pub.connect();

        pub.put("Hello over TLS!".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(1000);

        pub.close();
        sub.close();
    }
}
```

If you want to **pin the server certificate directly** (`cert.pem`) instead of or in addition to the CA chain, you can use [`ZenohConnection`](pure-java/src/main/java/io/mdudel/zenoh/purejava/connection/ZenohConnection.java) or [`TlsConfig`](pure-java/src/main/java/io/mdudel/zenoh/purejava/transport/TlsConfig.java):

```java
import io.mdudel.zenoh.purejava.session.ZenohSession;
import io.mdudel.zenoh.purejava.transport.TlsConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

TlsConfig tls = TlsConfig.builder()
        .caCertificatesPem(Path.of("/path/to/ca-chain.pem"))
        .pinnedServerCertificatePem(Path.of("/path/to/server-cert.pem"))
        .verifyHostname(false)
        .build();

try (ZenohSession session = ZenohSession.open("tls/127.0.0.1:7447", tls)) {
    long pubId = session.declarePublisher("demo/tls/smoke/hello");
    session.put("demo/tls/smoke/hello", "Hello with pinned cert!".getBytes(StandardCharsets.UTF_8));
    session.undeclarePublisher(pubId);
}
```

---

### Scenario C: TLS + Zenoh 1.9 RSA Public-Key Authentication (`ZenohRsaAuthenticator`)

If your router is configured with Zenoh's wire-level RSA public-key authentication (`transport.auth.pubkey` in `zenohd` JSON5 config) and you have an RSA **public key** (`-----BEGIN PUBLIC KEY-----`) plus its matching RSA **private key**, use [`ZenohConnection`](pure-java/src/main/java/io/mdudel/zenoh/purejava/connection/ZenohConnection.java) with [`ZenohRsaAuthenticator`](pure-java/src/main/java/io/mdudel/zenoh/purejava/connection/auth/ZenohRsaAuthenticator.java):

```java
import io.mdudel.zenoh.purejava.connection.ZenohConnection;
import io.mdudel.zenoh.purejava.connection.ZenohPublisher;
import io.mdudel.zenoh.purejava.connection.ZenohSubscriber;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class TlsPlusRsaPubKeySmokeTest {
    public static void main(String[] args) throws Exception {
        try (ZenohConnection conn = ZenohConnection.builder("tls://127.0.0.1:7447")
                .caCertificatePem(Path.of("/path/to/ca-chain.pem"))
                // Optional mTLS client cert/key if router also requires mTLS:
                // .clientCertificatePem(Path.of("/path/to/client-cert.pem"), Path.of("/path/to/client-key.pem"))
                // Zenoh 1.9 wire-level RSA pubkey authentication:
                .zenohRsaKeyPairPem(
                        Path.of("/path/to/client-public-key.pem"),
                        Path.of("/path/to/client-private-key.pem"))
                // Optional: verify the router's RSA public key during InitAck:
                // .zenohRemotePublicKeyPem(Path.of("/path/to/router-public-key.pem"))
                .verifyHostname(false)
                .connect()) {

            ZenohSubscriber sub = conn.subscribe("demo/tls/smoke/**", sample ->
                    System.out.println("RECV [" + sample.keyExpr() + "]: " + sample.payloadUtf8()));

            ZenohPublisher pub = conn.declarePublisher("demo/tls/smoke/hello");
            pub.put("Authenticated via TLS + Zenoh RSA PubKey!".getBytes(StandardCharsets.UTF_8));

            Thread.sleep(1000);
            pub.close();
            sub.close();
        }
    }
}
```

---

## 4. Starting a Local TLS `zenohd` Router (If You Need One for Testing)

If you don't already have a running TLS `zenohd` router, you can launch one using a JSON5 config:

```json5
// router-tls.json5
{
  mode: "router",
  listen: {
    endpoints: ["tls/0.0.0.0:7447"]
  },
  transport: {
    link: {
      tls: {
        root_ca_certificate: "/path/to/ca-chain.pem",
        listen_certificate: "/path/to/server-cert.pem",
        listen_private_key: "/path/to/server-key.pem",
        // Set to true for mTLS (Scenario A), or false for one-way Server TLS (Scenario B)
        verify_client: true
      }
    }
  }
}
```

Run `zenohd`:
```bash
zenohd -c router-tls.json5
```

---

## 5. Troubleshooting Common TLS Smoke-Test Issues

| Symptom / Exception | Cause | Fix |
| :--- | :--- | :--- |
| `IllegalArgumentException: Both clientCertPath and clientKeyPath must be provided together` | Only `--cert` was passed without `--key-file` (or vice versa). | For **mTLS**, pass both `--cert` and `--key-file` (where `--key-file` is the **private** key). For **one-way TLS**, omit both `--cert` and `--key-file` and pass only `--ca`. |
| `IllegalArgumentException: Unrecognized PEM private key format (expected PKCS#8 'PRIVATE KEY', PKCS#1 'RSA PRIVATE KEY', or SEC1 'EC PRIVATE KEY')` | You passed a **public key** (`-----BEGIN PUBLIC KEY-----`) or certificate to `--key-file`, or the private key is encrypted (`ENCRYPTED PRIVATE KEY`). | Ensure `--key-file` points to an unencrypted private key (`openssl pkey -in encrypted.key -out client-key.pem`). Both RSA and EC (P-256 / P-384 / P-521) keys are supported. |
| `SSLHandshakeException: No subject alternative DNS name matching ... found` or `No name matching ... found` | Connecting via IP or a hostname not listed in the router certificate's Subject Alternative Name (SAN). | Pass `--no-verify-hostname` on the CLI or `.verifyHostname(false)` on the builder during smoke testing, or regenerate the server cert with matching `subjectAltName = DNS:...,IP:...`. |
| `SSLHandshakeException: PKIX path building failed: ... unable to find valid certification path` | The router's certificate wasn't signed by the CA in `--ca` (or an intermediate CA is missing from `ca-chain.pem`). | Concatenate the intermediate CA(s) and Root CA into `ca-chain.pem` (`cat intermediate.pem root-ca.pem > ca-chain.pem`). |
| `SSLHandshakeException: Received fatal alert: certificate_required` or `bad_certificate` | The router has `verify_client: true` (mTLS enabled), but the client connected without `--cert` / `--key-file`, or the client cert isn't signed by the router's `root_ca_certificate`. | Supply a client certificate signed by the router's trusted CA and its matching client private key. |
