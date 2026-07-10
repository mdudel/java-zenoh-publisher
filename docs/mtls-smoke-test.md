# Sample mTLS Smoke Test Steps
## Zenoh Router + Java Publishers (Windows)

_A walk-through for validating both the JNI `mtls-publisher` and the
pure-Java `pure-java-mtls-publisher` samples end-to-end against a
local `zenohd` v1.7.2 with TLS + mutual client authentication._

---

## Prerequisites

Open **PowerShell** (**not** `cmd.exe`, several commands rely on PowerShell string handling) and run:

```powershell
java -version
mvn -version
git --version
keytool -help | Select-Object -First 1
```

Expected: all four commands print without error.

- `java` must report **17 or higher**. `winget install EclipseAdoptium.Temurin.21.JDK` if missing.
- `mvn` must report **3.6+**. `winget install Apache.Maven` if missing.
- `git` any recent version. `winget install Git.Git` if missing.
- `keytool` ships with the JDK; if this fails your `JAVA_HOME\bin` isn't on `PATH`.

---

### Get `zenohd`

**Download `zenohd`.**
You'll download the pre-built Windows binary from Eclipse, no source build required.

```powershell
# Landing directory (used throughout this doc).
New-Item -ItemType Directory -Force D:\ZENOH | Out-Null
cd D:\ZENOH

# Grab the Windows release archive. Note the "-standalone" suffix --
# Eclipse also ships a "gnu" build with a similar name, but MSVC is
# what you want on stock Windows. Eclipse's mirror-selector URL (the
# one on download.eclipse.org) does NOT work with Invoke-WebRequest;
# it returns an HTML mirror-picker page. Use the direct GitHub URL.
curl.exe -L -o zenoh-1.7.2-x86_64-pc-windows-msvc-standalone.zip `
    https://github.com/eclipse-zenoh/zenoh/releases/download/1.7.2/zenoh-1.7.2-x86_64-pc-windows-msvc-standalone.zip

# Sanity-check the download: ZIP magic bytes should be "50 4B 03 04".
# If instead you see "3C 21 44 4F 43" ("<!DOC...") the URL 404'd and
# saved an HTML error page in place of the archive; you'll get a
# cryptic "End of Central Directory record could not be found" when
# you Expand-Archive it.
$magic = Get-Content .\zenoh-1.7.2-x86_64-pc-windows-msvc-standalone.zip `
    -TotalCount 4 -Encoding Byte |
    ForEach-Object { '{0:X2}' -f $_ } |
    Join-String -Separator ' '
if ($magic -ne '50 4B 03 04') {
    throw "downloaded file is not a ZIP (magic=$magic); check the URL and retry"
}
```

**Unzip in place.**

```powershell
Expand-Archive -Force zenoh-1.7.2-x86_64-pc-windows-msvc-standalone.zip -DestinationPath .
```

You should now have `D:\ZENOH\zenohd.exe` (and a few plugin DLLs).

```
PS D:\ZENOH> dir
    Directory: D:\ZENOH
Mode                 LastWriteTime         Length Name
----                 -------------         ------ ----
d-----         7/10/2026   1:45 PM                deps
-a----         7/10/2026   1:45 PM       11801088 zenohd.exe
-a----         7/10/2026   1:45 PM        2769408 zenoh_plugin_rest.dll
-a----         7/10/2026   1:45 PM        2642432 zenoh_plugin_storage_manager.dll
```

Verify it runs:

```powershell
.\zenohd.exe --version
```

Expected output similar to this (last line):
```
zenohd v1.7.2 built with rustc 1.85.0 (...)
```

If Windows blocks the `.exe` (SmartScreen), right-click → **Properties** → **Unblock** → **OK**, then retry.

---

### Cut certificates with `keytool`

You'll create a small chain:

```
CA (self-signed)
 router cert  (CN=localhost, SAN=dns:localhost + ip:127.0.0.1)
 client cert  (CN=client)
```

All in `D:\ZENOH\certs\`, in both PEM (for `zenohd` and the samples) and PKCS12 (for the pure-Java sample's alternative code path).

> **About `keytool`:** it's a JDK utility that manipulates Java-native PKCS12 keystores. Getting **PEM** files out of it takes a few extra steps because `keytool` doesn't natively export private keys as PEM. We work around this by exporting to PKCS12 first and then extracting with the small `tools/Pkcs12ToPem.java` utility bundled with this repo. **Zero third-party tools.**

#### Certs: Setup

```powershell
New-Item -ItemType Directory -Force D:\ZENOH\certs | Out-Null
cd D:\ZENOH\certs

# A consistent password for every keystore below. The private keys we
# export as PEM will be UNENCRYPTED, so this password only guards the
# on-disk .p12 files; it never travels over the wire.
$pw = "changeit"
```

#### Certs: Create the CA (self-signed root)

```powershell
keytool -genkeypair -alias ca -keyalg RSA -keysize 2048 -validity 3650 `
    -keystore ca.p12 -storetype PKCS12 -storepass $pw -keypass $pw `
    -dname "CN=zenoh-smoketest-ca, OU=dev, O=mdudel, C=DE" `
    -ext "BasicConstraints=ca:true,pathlen:0" `
    -ext "KeyUsage=keyCertSign,cRLSign"

# Export the CA public certificate as PEM.
keytool -exportcert -alias ca -keystore ca.p12 -storetype PKCS12 -storepass $pw `
    -rfc -file ca.pem
```

#### Certs: Create the router keypair, sign it with the CA

```powershell
# Generate a keypair for the router. CN=localhost + SAN covers the two
# addresses clients will connect to (dns:localhost + ip:127.0.0.1).
keytool -genkeypair -alias router -keyalg RSA -keysize 2048 -validity 3650 `
    -keystore router.p12 -storetype PKCS12 -storepass $pw -keypass $pw `
    -dname "CN=localhost, OU=router, O=mdudel, C=DE" `
    -ext "SAN=dns:localhost,ip:127.0.0.1" `
    -ext "ExtendedKeyUsage=serverAuth"

# Produce a CSR (certificate signing request) for the router.
keytool -certreq -alias router -keystore router.p12 -storetype PKCS12 -storepass $pw `
    -file router.csr

# Sign the CSR with the CA. This is the step that turns a self-signed
# router cert into one that clients validating against ca.pem will trust.
keytool -gencert -alias ca -keystore ca.p12 -storetype PKCS12 -storepass $pw `
    -infile router.csr -outfile router-signed.pem -validity 3650 `
    -ext "SAN=dns:localhost,ip:127.0.0.1" `
    -ext "ExtendedKeyUsage=serverAuth"

# Import the CA + signed router cert back into router.p12 so the router
# keystore now holds a proper cert chain rooted at the CA.
keytool -importcert -alias ca -keystore router.p12 -storetype PKCS12 -storepass $pw `
    -file ca.pem -noprompt
keytool -importcert -alias router -keystore router.p12 -storetype PKCS12 -storepass $pw `
    -file router-signed.pem
```

#### Certs: Create the client keypair, sign it with the CA

Same recipe with `client` in place of `router`. The client cert doesn't need SANs (nothing verifies its hostname), but does need `ExtendedKeyUsage=clientAuth` so the router accepts it for mTLS.

```powershell
keytool -genkeypair -alias client -keyalg RSA -keysize 2048 -validity 3650 `
    -keystore client.p12 -storetype PKCS12 -storepass $pw -keypass $pw `
    -dname "CN=client, OU=dev, O=mdudel, C=DE" `
    -ext "ExtendedKeyUsage=clientAuth"

keytool -certreq -alias client -keystore client.p12 -storetype PKCS12 -storepass $pw `
    -file client.csr

keytool -gencert -alias ca -keystore ca.p12 -storetype PKCS12 -storepass $pw `
    -infile client.csr -outfile client-signed.pem -validity 3650 `
    -ext "ExtendedKeyUsage=clientAuth"

keytool -importcert -alias ca -keystore client.p12 -storetype PKCS12 -storepass $pw `
    -file ca.pem -noprompt
keytool -importcert -alias client -keystore client.p12 -storetype PKCS12 -storepass $pw `
    -file client-signed.pem
```

#### Certs: Extract PEM certs and PEM (unencrypted) private keys

`zenohd` and the JNI publisher sample both want:
- CA cert as PEM
- Router (or client) cert as PEM
- Router (or client) private key as **unencrypted** PEM

The router-cert and client-cert PEMs already exist as `router-signed.pem` and `client-signed.pem` from earlier — rename them for clarity:

```powershell
Move-Item -Force router-signed.pem router-cert.pem
Move-Item -Force client-signed.pem client-cert.pem
```

For the private keys, use the `tools/Pkcs12ToPem.java` utility from this repo. Windows PowerShell 5.1 runs on .NET Framework 4.x and lacks `RSA.ExportPkcs8PrivateKey()` (that API was added in .NET Core 3.0), so the "just do it in PowerShell" route doesn't work on stock Windows. The Java converter uses only the JDK you already have:

```powershell
# One-time compile of the converter (adjust the repo path to yours).
cd D:\DEV\PROJECTS\Zenoh\java-zenoh-publisher\tools
javac Pkcs12ToPem.java

# Convert router + client keystores. Overwrites the router-cert.pem
# and client-cert.pem you just renamed above (same underlying bytes,
# cleaner single-block PEM output from the converter).
java Pkcs12ToPem D:\ZENOH\certs\router.p12 router changeit D:\ZENOH\certs\router-cert.pem D:\ZENOH\certs\router-key.pem
java Pkcs12ToPem D:\ZENOH\certs\client.p12 client changeit D:\ZENOH\certs\client-cert.pem D:\ZENOH\certs\client-key.pem
```

Expected output for each command:
```
wrote D:\ZENOH\certs\router-cert.pem
wrote D:\ZENOH\certs\router-key.pem
```

#### Certs: Verify the final cert bundle

```powershell
cd D:\ZENOH\certs
dir *.pem, *.p12
```

You should see these files (plus a few `.csr` files you can delete):

```
ca.pem              CA public cert
ca.p12              CA keystore (has CA private key; keep for signing more clients later)
router-cert.pem     router public cert, signed by ca.pem
router-key.pem      router private key, unencrypted PEM (this is what zenohd wants)
router.p12          router PKCS12 keystore (same material, keystore format)
client-cert.pem     client public cert, signed by ca.pem
client-key.pem      client private key, unencrypted PEM (used by both publishers)
client.p12          client PKCS12 keystore (used by pure-Java's PKCS12 code path)
```

Clean up the intermediate CSR files:

```powershell
Remove-Item -Force *.csr
```

**Security note:** these keys are **unencrypted on disk**. That's fine for a smoke test on your dev laptop. Do not commit them to git and do not reuse them for anything real. `client-key.pem` should be `chmod 600` equivalent, but Windows NTFS defaults are enough for a dev-laptop smoke test.

---

## Configure `zenohd` for TLS + mTLS

Create `D:\ZENOH\zenohd-mtls.json5`. This tells `zenohd` to:
- listen on both `tls/0.0.0.0:7447` (IPv4) AND `tls/[::]:7447` (IPv6),
- present `router-cert.pem` signed by `ca.pem`,
- **require** client certs signed by the same `ca.pem`.

**Why both bindings?** IPv6-only (`tls/[::]:7447` alone) works fine with the JNI/Rust client stack (which falls back through resolved addresses), but Java stdlib's `Socket.connect("localhost", ...)` on Windows resolves to `127.0.0.1` first and does **not** fall back to `::1`. The pure-Java publisher will get `Connection refused` against an IPv6-only listener. Binding both keeps `localhost` working for every client.

**Why `enable_mtls: true`?** Presence of `root_ca_certificate` alone is **not** enough on zenoh 1.7 — that key only says "here's the CA I trust for validating client certs *if any arrive*." The router will not *demand* a client cert unless `enable_mtls: true` is also set. Without it, clients silently fall back to server-only TLS and the smoke test looks like it's passing when it isn't.

```powershell
$config = @'
{
  mode: "router",

  listen: {
    endpoints: [
      "tls/0.0.0.0:7447",
      "tls/[::]:7447"
    ],
  },

  transport: {
    link: {
      tls: {
        listen_certificate: "D:/ZENOH/certs/router-cert.pem",
        listen_private_key: "D:/ZENOH/certs/router-key.pem",
        root_ca_certificate: "D:/ZENOH/certs/ca.pem",

        // CRUCIAL: on zenoh 1.7 this MUST be true for the router to
        // demand a client certificate. Without it, root_ca_certificate
        // is only used to VALIDATE certs if a client happens to
        // present one -- but the router won't REQUIRE one, and
        // clients will silently fall back to server-only TLS.
        // The Common Name diagnostic in the "Confirm" step below is
        // the only place this silent failure surfaces.
        enable_mtls: true,
      },
    },
  },
}
'@
Set-Content -Path D:\ZENOH\zenohd-mtls.json5 -Value $config -Encoding UTF8
```

> **Path style:** `zenohd` on Windows accepts either forward slashes (`D:/ZENOH/certs/...`) or **doubled** backslashes (`D:\\ZENOH\\certs\\...`) inside the JSON5 file. Single backslashes silently become invalid escape sequences. Forward slashes are less error-prone.

### Start `zenohd` in mTLS mode

**Terminal A** (leave running for the whole smoke test):

```powershell
cd D:\ZENOH
$env:RUST_LOG = "debug"
.\zenohd.exe -c .\zenohd-mtls.json5
```

`RUST_LOG=debug` is **required** for this smoke test. At the default INFO level, `zenohd 1.7.2` does not print anything when a client connects, disconnects, or completes a TLS/mTLS handshake — only listener startup and hard errors. Without DEBUG, Terminal A will look eerily silent after a successful publisher run.

Expected startup lines:
```
INFO ThreadId(01) zenohd: zenohd v1.7.2 built with rustc 1.85.0 (...)
DEBUG ... zenoh::net::runtime::orchestrator: Listener added: tls/0.0.0.0:7447
DEBUG ... zenoh::net::runtime::orchestrator: Listener added: tls/[::]:7447
INFO ThreadId(01) zenoh::net::runtime: Using ZID: <random-hex>
INFO ... Zenoh can be reached at: tls/<your-ip>:7447
```

Both `Listener added` lines confirm the dual bind worked. If you see only one, check the JSON5 `listen.endpoints` array.

If instead you see:
```
Error: Missing or invalid TLS server key/certificate
```
double-check the `listen_private_key` and `listen_certificate` paths in `zenohd-mtls.json5` — Windows path separators are the #1 cause.

### Verify the port is up (Terminal B, one-off check)

Open a **second** PowerShell:

```powershell
Test-NetConnection -ComputerName localhost -Port 7447
```

Expect `TcpTestSucceeded : True`. Windows Firewall may prompt the first time — **allow** it.

### Prove mTLS is enforced

An unauthenticated TCP client should **fail** to complete the TLS handshake. Confirm this by trying a plain HTTPS connect that doesn't present a client cert:

```powershell
try {
    $tcp = New-Object System.Net.Sockets.TcpClient
    $tcp.Connect("localhost", 7447)
    $ssl = New-Object System.Net.Security.SslStream($tcp.GetStream(), $false,
        { param($s,$c,$ch,$e) $true })   # trust everything for this probe
    # Handshake WITHOUT presenting a client cert.
    $ssl.AuthenticateAsClient("localhost")
    Write-Host "UH-OH: handshake succeeded without a client cert. mTLS is NOT enforced."
} catch {
    Write-Host "GOOD: handshake refused without client cert. mTLS IS enforced."
    Write-Host "  detail: $($_.Exception.Message)"
} finally {
    if ($tcp) { $tcp.Close() }
}
```

Expected output:
```
GOOD: handshake refused without client cert. mTLS IS enforced.
  detail: ... (varies; will be a "connection closed" or "certificate required" message)
```

If instead you see the `UH-OH` line, `zenohd` accepted a client with no cert. Double-check that `enable_mtls: true` is present in `zenohd-mtls.json5` and restart `zenohd`.

**Simultaneously**, `zenohd`'s log (Terminal A) should show a handshake error for that probe — a `TLS handshake failed` or `MissingRequiredCertificate` line. That's the mirror-image confirmation that mTLS enforcement is happening on the router side too.

---

## Clone the repo and install prerequisites

**Terminal C** (from here on, keep Terminals A + B running; C is where you build and run):

```powershell
cd D:\DEV\PROJECTS\Zenoh
git clone https://github.com/mdudel/java-zenoh-publisher.git
cd java-zenoh-publisher
```

### Install the pure-Java module into your local Maven cache

This is a **one-time** step. Only needed because the pure-Java sample resolves `io.mdudel:java-zenoh-publisher-pure` from `~/.m2/repository`, and there's no public release yet.

```powershell
mvn -B -f pure-java\pom.xml install
```

Expect:
```
[INFO] Tests run: 242 (or whatever number of tests exist currently), Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Installing D:\...\pure-java\target\java-zenoh-publisher-pure-0.0.1-SNAPSHOT.jar
        to C:\Users\<you>\.m2\repository\io\mdudel\java-zenoh-publisher-pure\0.0.1-SNAPSHOT\java-zenoh-publisher-pure-0.0.1-SNAPSHOT.jar
```

**All tests must pass** before you continue.

---

## mTLS with the JNI publisher

The JNI sample takes six positional args:

```
mtls-publisher <endpoint> <keyExpr> <rootCa> <clientCert> <clientKey> [verifyHostname]
```

### Build

```powershell
cd D:\DEV\PROJECTS\Zenoh\java-zenoh-publisher\samples\mtls-publisher
mvn -B package
```

Expect `BUILD SUCCESS` and a jar at `target\mtls-publisher-0.1.0.jar` (~30 MB — the JNI native binary is bundled).

### Publish

```powershell
java -jar target\mtls-publisher-0.1.0.jar `
    tls/localhost:7447 `
    demo/mtls/jni `
    D:\ZENOH\certs\ca.pem `
    D:\ZENOH\certs\client-cert.pem `
    D:\ZENOH\certs\client-key.pem `
    true
```

Expected output:
```
[ZenohClient] starting connectEndpoint="tls/localhost:7447" ...
[ZenohClient] started → tls/localhost:7447 key=demo/mtls/jni
[MtlsPublisher] published 12B to key=demo/mtls/jni via tls/localhost:7447
[ZenohClient] stopped
```

### Confirm `zenohd` saw it AND authenticated the client cert

Flip to **Terminal A** (`zenohd`). Within a second or two of the publisher running, look for the **critical** line:

```
DEBUG ... zenoh_link_tls::unicast: Accepted TLS connection on 127.0.0.1:7447: 127.0.0.1:xxxxx.
                                    Common Name: client.
```

**`Common Name: client`** (or whatever CN you used at cert-gen time) is the single strongest proof that mTLS worked end to end — the router accepted a TLS handshake in which the client presented a cert signed by the trusted CA.

**If you see `Common Name: None` instead**, the TLS handshake succeeded WITHOUT the client presenting a cert. That means:
- Your `zenohd-mtls.json5` is missing `enable_mtls: true`. Add that key, restart the router, retry.
- OR the JNI publisher's cert files were unreadable/malformed and the JNI stack silently fell back to server-only TLS.

A `[MtlsPublisher] published 12B ...` output on the client side is necessary but **not sufficient** — `ZenohClient.publish()` also succeeds over plain server-only TLS. Only the router's `Common Name` line distinguishes real mTLS from silent-fallback TLS.

Other useful lines you should see around the same time:

```
DEBUG ... zenoh_transport::unicast::manager: New transport opened between <zid> and <zid>
DEBUG ... zenoh_transport::unicast::universal::link:
            RX task failed: peer closed connection without sending TLS close_notify
```

The `close_notify` line is benign — Java's `SSLSocket.close()` teardown is slightly less graceful than what `rustls` prefers. It does not indicate a real failure; the publisher's mTLS session was successful and this is just the connection ending.

Other failure modes if the run goes wrong:

- `TLS handshake failed` — walk back through cert generation. Most common cause: client cert missing `ExtendedKeyUsage=clientAuth`.
- `certificate verify failed` on the client side — the router cert isn't signed by the CA the client trusts. Check `router-cert.pem` was signed by `ca.pem`, not self-signed.
- **Nothing new in Terminal A** — you forgot `RUST_LOG=debug` before starting `zenohd`. Ctrl-C it, `$env:RUST_LOG = "debug"`, restart, retry.

---

## mTLS with the pure-Java publisher

The `samples/pure-java-mtls-publisher/` sample takes the **same six positional args** as the JNI `mtls-publisher` sample above. The only difference is which Java class runs behind `main()` — `PureJavaZenohPublisher` here vs `ZenohClient` there. Zero JNI, zero third-party runtime deps: just the JDK and our clean-room wire codec.

### Build

```powershell
cd D:\DEV\PROJECTS\Zenoh\java-zenoh-publisher\samples\pure-java-mtls-publisher
mvn -B package
```

Expect `BUILD SUCCESS` and a jar at `target\pure-java-mtls-publisher-0.1.0.jar` (~95 KB — vs ~30 MB for the JNI equivalent, which is the whole point of the pure-Java module).

### Publish

```powershell
java -jar target\pure-java-mtls-publisher-0.1.0.jar `
    tls/localhost:7447 `
    demo/mtls/pure `
    D:\ZENOH\certs\ca.pem `
    D:\ZENOH\certs\client-cert.pem `
    D:\ZENOH\certs\client-key.pem `
    true
```

Expected output:
```
[PureMtlsPublisher] endpoint=tls/localhost:7447 key=demo/mtls/pure ...
INFO: PureJavaZenohPublisher.start() endpoint=tls/localhost:7447 ... verifyHostname=true ...
[PureMtlsPublisher] session OPEN
[PureMtlsPublisher] published 27B to key=demo/mtls/pure via tls/localhost:7447 (sent=1)
INFO: PureJavaZenohPublisher.stop()
```

### Confirm `zenohd` saw the pure-Java publisher

Same check as the JNI section, but for a NEW session. In Terminal A, find a fresh `Accepted TLS connection` block with a new peer ZID:

```
DEBUG ... zenoh_link_tls::unicast: Accepted TLS connection on 127.0.0.1:7447: 127.0.0.1:xxxxx.
                                    Common Name: client.
```

**`Common Name: client`** = mTLS worked end to end with the clean-room pure-Java stack. That's byte-level interop proof: the from-scratch INIT/OPEN handshake + FRAME/PUSH/PUT wire codec negotiated a full Zenoh session over an mTLS-authenticated TLS 1.3 link to production `zenohd 1.7.2`, using the pure-Java `PemLoader` to read the same PEM cert files the JNI sample used above.

Same failure modes as the JNI section apply if it doesn't work.

---

## Optional: PKCS12 keystore code path

`PureJavaZenohPublisher` also accepts a **PKCS12** combined keystore for client authentication. This exercises a different code path inside `TlsConfig` (native `KeyStore.getInstance("PKCS12")` load, not the PEM parser). Worth confirming, but not strictly required for the mTLS smoke test.

The sample takes the same positional args — just point `clientCert` and `clientKey` at the SAME `.p12` file (auto-detection routes on the extension):

```powershell
java -jar target\pure-java-mtls-publisher-0.1.0.jar `
    tls/localhost:7447 `
    demo/mtls/pure-p12 `
    D:\ZENOH\certs\ca.pem `
    D:\ZENOH\certs\client.p12 `
    D:\ZENOH\certs\client.p12 `
    true
```

Assumes your `client.p12`'s password is `changeit` — that's the default the pure-Java facade uses. For a different password, write a small wrapper program that calls `PureJavaZenohPublisher.Builder.keyStorePassword(char[])` explicitly (the sample's positional-args interface deliberately doesn't take a password to avoid shell-history leaks).

Expected outcome: another `Accepted TLS connection ... Common Name: client.` line in Terminal A with a fresh peer ZID.

---

## Cleanup

Stop `zenohd` (Terminal A): `Ctrl-C`.

If you want to keep the smoke-test setup for repeat runs, leave everything as-is — the cert bundle is valid for 10 years. If you want to tear it all down:

```powershell
Remove-Item -Recurse -Force D:\ZENOH\certs
Remove-Item -Force D:\ZENOH\zenohd-mtls.json5
# Keep zenohd.exe unless you're done with it entirely.
```

---

## Troubleshooting quick reference

| Symptom | Likely cause |
|---|---|
| `Expand-Archive` fails with "End of Central Directory record could not be found" | Downloaded file isn't a real ZIP — probably an HTML error page. Re-check the URL; use the GitHub release URL, not `download.eclipse.org`. |
| `zenohd` won't start, "Missing or invalid TLS server key/certificate" | Path syntax in `zenohd-mtls.json5` — use forward slashes. |
| `zenohd` starts but the mTLS enforcement probe says `UH-OH: handshake succeeded without a client cert` | `enable_mtls: true` is missing from `zenohd-mtls.json5`. `root_ca_certificate` alone does NOT enable enforcement on zenoh 1.7+. |
| `zenohd`'s log shows `Common Name: None` after a "successful" publish | Same as above — client did NOT present a cert; both sides negotiated server-only TLS. |
| Publisher: `Connection refused` when connecting to `tls/localhost:7447` | Router is bound to IPv6 only (`tls/[::]:7447`) but Java resolves `localhost` to IPv4 first. Add `tls/0.0.0.0:7447` to `listen.endpoints`. |
| Publisher: `TLS handshake failed`, `certificate verify failed` | Router cert isn't signed by the CA the client trusts, OR SAN doesn't cover the hostname/IP you connected to. Check `router-cert.pem` was signed by `ca.pem`. |
| Publisher: `certificate required` from `zenohd` side | Client cert wasn't presented. Check `client-cert.pem` + `client-key.pem` paths. |
| Publisher: `unable to find valid certification path` | `ca.pem` isn't being loaded as the client's trust anchor. Check the third positional arg to the publisher. |
| Pure-Java publisher: `PEM client authentication requires BOTH clientCertPath AND clientKeyPath` | Only one of the two PEM paths was set. mTLS in PEM mode needs both. |
| Windows: `keytool` not recognized | `%JAVA_HOME%\bin` isn't on `PATH`. |
| Terminal A silent after a successful publisher run | You started `zenohd` without `$env:RUST_LOG = "debug"`. Ctrl-C, set the env var, restart. |

---

## What this smoke test proves

If both publishers (JNI PEM, pure-Java PEM) successfully connect to `zenohd` under mTLS with `Common Name: client` in the router log, you've validated:

- The **CA chain** (custom-generated CA → router cert; CA → client cert)
- The **router's** TLS + mTLS enforcement configuration
- The **JNI** publisher's PEM cert loading (Rust `zenoh-java` binding)
- The **pure-Java** publisher's PEM cert loading (`PemLoader` + `TlsConfig.trustStorePem` + `keyStorePem`, all clean-room JDK stdlib)
- Optionally, the **pure-Java** publisher's PKCS12 keystore loading (alternate code path)
- **Wire-level interoperability** between the pure-Java implementation and production `zenohd` v1.7.2 — the highest-fidelity interop check possible short of a multi-router federation test.

The pure-Java module has **zero third-party runtime dependencies**; everything above works with JDK 17+ stdlib only.
