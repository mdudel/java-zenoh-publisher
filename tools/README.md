# tools/

Small self-contained utilities used by the smoke-test docs and by
developers doing one-off cert / keystore work. **Not part of the
runtime distribution.** Each file compiles and runs on its own with
`javac` + `java`; no Maven build needed.

## Pkcs12ToPem.java

Converts a PKCS12 keystore into a pair of PEM files:
- `<out-cert.pem>` -- `-----BEGIN CERTIFICATE-----` block
- `<out-key.pem>`  -- `-----BEGIN PRIVATE KEY-----` block (PKCS#8, **unencrypted**)

That's the pair `zenohd`'s `listen_certificate` / `listen_private_key`
wants, and the same shape the `mtls-publisher` JNI sample expects for
`rootCa.pem` / `client-cert.pem` / `client-key.pem`.

### Why this exists

Windows PowerShell 5.1 (the default on Windows 10) runs on
.NET Framework 4.x, which lacks `RSA.ExportPkcs8PrivateKey()`
(added in .NET Core 3.0). So the natural
"just do it in PowerShell" one-liner doesn't work on stock Windows.
This tiny Java program uses the JDK you already have and works
everywhere the pure-Java module works. Zero third-party dependencies.

### Usage

```
javac Pkcs12ToPem.java
java  Pkcs12ToPem <input.p12> <alias> <password> <out-cert.pem> <out-key.pem>
```

Example: convert a keytool-generated router keystore to the PEM pair
zenohd wants for its TLS listener:

```
java Pkcs12ToPem router.p12 router changeit router-cert.pem router-key.pem
```

Same for a client keystore (for the `mtls-publisher` or
`PureJavaZenohPublisher` client-cert path):

```
java Pkcs12ToPem client.p12 client changeit client-cert.pem client-key.pem
```

### Security note

The output `.pem` key file is **unencrypted PKCS#8**. That's what
zenohd and Java TLS APIs consume without a password. Do not commit
these files or reuse them for anything real; treat them as ephemeral
smoke-test material.
