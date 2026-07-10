/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0.
 *
 * Small standalone converter used by the mTLS smoke test to turn a
 * PKCS12 keystore into a PEM cert + unencrypted PKCS#8 PEM key pair,
 * matching what zenohd and the JNI publisher sample expect.
 *
 * WHY THIS EXISTS
 * ---------------
 * Windows PowerShell 5.1 runs on .NET Framework 4.x, which does not
 * expose RSA.ExportPkcs8PrivateKey(). That method was added in
 * .NET Core 3.0 (2019) and is only available in PowerShell 7+.
 *
 * The pure-Java module already needs a JDK 17+, so a tiny Java
 * converter is the shortest zero-third-party-dep path that works
 * on every Windows box that meets the module's basic requirements.
 *
 * USAGE
 * -----
 *   javac Pkcs12ToPem.java
 *   java  Pkcs12ToPem <input.p12> <alias> <password> <out-cert.pem> <out-key.pem>
 *
 * EXAMPLE
 * -------
 *   javac Pkcs12ToPem.java
 *   java  Pkcs12ToPem router.p12 router changeit router-cert.pem router-key.pem
 *   java  Pkcs12ToPem client.p12 client changeit client-cert.pem client-key.pem
 *
 * OUTPUT FORMAT
 * -------------
 *   <out-cert.pem> - "-----BEGIN CERTIFICATE-----" ... "-----END CERTIFICATE-----"
 *   <out-key.pem>  - "-----BEGIN PRIVATE KEY-----" ... "-----END PRIVATE KEY-----"
 *                    (PKCS#8 unencrypted; what zenohd's listen_private_key wants)
 */
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Base64;

public final class Pkcs12ToPem {

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            System.err.println(
                "usage: Pkcs12ToPem <input.p12> <alias> <password> <out-cert.pem> <out-key.pem>");
            System.exit(2);
        }
        Path   in     = Paths.get(args[0]).toAbsolutePath();
        String alias  = args[1];
        char[] pw     = args[2].toCharArray();
        Path   outCer = Paths.get(args[3]).toAbsolutePath();
        Path   outKey = Paths.get(args[4]).toAbsolutePath();

        if (!Files.isReadable(in)) {
            System.err.println("cannot read input: " + in);
            System.exit(3);
        }

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var fis = Files.newInputStream(in)) {
            ks.load(fis, pw);
        }

        if (!ks.containsAlias(alias)) {
            System.err.println("alias '" + alias + "' not found in " + in
                + "; available aliases:");
            var e = ks.aliases();
            while (e.hasMoreElements()) System.err.println("  - " + e.nextElement());
            System.exit(4);
        }

        Certificate cert = ks.getCertificate(alias);
        if (cert == null) {
            System.err.println("no certificate under alias '" + alias + "'");
            System.exit(5);
        }
        Key key = ks.getKey(alias, pw);
        if (key == null) {
            System.err.println("no private key under alias '" + alias
                + "' (this alias may hold only a trustedCertEntry)");
            System.exit(6);
        }
        // JCA guarantees PKCS#8 encoding for private keys retrieved this way.
        if (!"PKCS#8".equalsIgnoreCase(key.getFormat())) {
            System.err.println("unexpected key format: " + key.getFormat()
                + " (expected PKCS#8)");
            System.exit(7);
        }

        writePem(outCer, "CERTIFICATE", cert.getEncoded());
        writePem(outKey, "PRIVATE KEY", key.getEncoded());

        System.out.println("wrote " + outCer);
        System.out.println("wrote " + outKey);
    }

    /** Write a single-block PEM file: "-----BEGIN <label>-----" + base64(body) + "-----END <label>-----". */
    private static void writePem(Path out, String label, byte[] body) throws Exception {
        String b64 = Base64.getMimeEncoder(64, new byte[] { '\n' }).encodeToString(body);
        try (var w = new OutputStreamWriter(Files.newOutputStream(out), StandardCharsets.US_ASCII)) {
            w.write("-----BEGIN ");
            w.write(label);
            w.write("-----\n");
            w.write(b64);
            w.write("\n-----END ");
            w.write(label);
            w.write("-----\n");
        }
    }
}
