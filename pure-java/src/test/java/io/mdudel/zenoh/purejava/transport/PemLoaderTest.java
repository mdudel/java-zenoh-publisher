/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.transport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the PEM parser. Uses the PEM files committed at
 * {@code src/test/resources/} that were generated from the same PKCS12
 * keystores {@link TlsTransportTest} uses &mdash; so a bug here shows
 * up as a mismatch against the reference PKCS12 material.
 */
class PemLoaderTest {

    @Test void loadsSingleCertFromPem() throws IOException {
        List<X509Certificate> certs = PemLoader.readCertificates(resource("server-cert.pem"));
        assertEquals(1, certs.size());
        X509Certificate c = certs.get(0);
        assertTrue(c.getSubjectX500Principal().getName().contains("CN=localhost"),
                "cert subject should be CN=localhost: " + c.getSubjectX500Principal());
    }

    @Test void loadsClientCert() throws IOException {
        List<X509Certificate> certs = PemLoader.readCertificates(resource("client-cert.pem"));
        assertEquals(1, certs.size());
        assertTrue(certs.get(0).getSubjectX500Principal().getName().contains("CN=client"));
    }

    @Test void loadsChainOfMultipleCerts() throws IOException {
        // Concatenate the two cert PEMs into one file; parser should return both.
        Path tmp = Files.createTempFile("chain-", ".pem");
        tmp.toFile().deleteOnExit();
        Files.writeString(tmp,
                Files.readString(resource("server-cert.pem"))
                        + "\n"
                        + Files.readString(resource("client-cert.pem")));
        List<X509Certificate> certs = PemLoader.readCertificates(tmp);
        assertEquals(2, certs.size());
    }

    @Test void loadsPkcs8UnencryptedPrivateKey() throws IOException {
        PrivateKey k = PemLoader.readPrivateKey(resource("server-key.pem"));
        assertNotNull(k);
        assertEquals("RSA", k.getAlgorithm());
    }

    @Test void loadsPkcs1RsaPrivateKeyViaAutoWrap() throws IOException {
        // The pkcs1 flavour ("-----BEGIN RSA PRIVATE KEY-----") is what
        // legacy `openssl req` still emits. Parser should wrap it in PKCS#8
        // in-memory before decoding, so users don't need `openssl pkcs8` first.
        PrivateKey k = PemLoader.readPrivateKey(resource("client-key-pkcs1.pem"));
        assertNotNull(k);
        assertEquals("RSA", k.getAlgorithm());
    }

    @Test void loadedKeyAndCertMatch() throws IOException {
        // Sanity: the public key in the cert must correspond to the private key.
        PrivateKey key   = PemLoader.readPrivateKey(resource("client-key.pem"));
        X509Certificate cert = PemLoader.readCertificates(resource("client-cert.pem")).get(0);
        // For RSA, comparing moduli is the cheap check.
        java.security.interfaces.RSAPrivateKey rk = (java.security.interfaces.RSAPrivateKey) key;
        java.security.interfaces.RSAPublicKey  pk = (java.security.interfaces.RSAPublicKey)  cert.getPublicKey();
        assertEquals(pk.getModulus(), rk.getModulus(),
                "cert's public key modulus should match private key modulus");
    }

    @Test void pkcs1AutoWrapKeyMatchesPkcs8Version() throws IOException {
        // The two client-key files were derived from the same PKCS12; both
        // should decode to a key with an identical RSA modulus.
        PrivateKey p8 = PemLoader.readPrivateKey(resource("client-key.pem"));
        PrivateKey p1 = PemLoader.readPrivateKey(resource("client-key-pkcs1.pem"));
        java.security.interfaces.RSAPrivateKey r8 = (java.security.interfaces.RSAPrivateKey) p8;
        java.security.interfaces.RSAPrivateKey r1 = (java.security.interfaces.RSAPrivateKey) p1;
        assertEquals(r8.getModulus(), r1.getModulus(),
                "PKCS#1 auto-wrap must produce the same key as native PKCS#8");
    }

    @Test void missingCertBlockRejected() throws IOException {
        Path tmp = Files.createTempFile("no-cert-", ".pem");
        tmp.toFile().deleteOnExit();
        Files.writeString(tmp, "no PEM here\n");
        IOException e = assertThrows(IOException.class,
                () -> PemLoader.readCertificates(tmp));
        assertTrue(e.getMessage().contains("no CERTIFICATE PEM block"),
                "message was: " + e.getMessage());
    }

    @Test void missingKeyBlockRejected() throws IOException {
        Path tmp = Files.createTempFile("no-key-", ".pem");
        tmp.toFile().deleteOnExit();
        Files.writeString(tmp, "not a private key\n");
        IOException e = assertThrows(IOException.class,
                () -> PemLoader.readPrivateKey(tmp));
        assertTrue(e.getMessage().contains("no PRIVATE KEY"),
                "message was: " + e.getMessage());
    }

    @Test void encryptedPkcs8KeyRejectedWithHelpfulMessage() throws IOException {
        // Synthesise a file with an ENCRYPTED PKCS#8 block; parser should
        // point the user at the openssl conversion.
        Path tmp = Files.createTempFile("encrypted-", ".pem");
        tmp.toFile().deleteOnExit();
        Files.writeString(tmp,
                "-----BEGIN ENCRYPTED PRIVATE KEY-----\n"
                        + "MIIBrTBXBgkqhkiG9w0BBQ0wSjApBgkqhkiG9w0BBQwwHAQI\n"
                        + "-----END ENCRYPTED PRIVATE KEY-----\n");
        IOException e = assertThrows(IOException.class,
                () -> PemLoader.readPrivateKey(tmp));
        assertTrue(e.getMessage().contains("openssl pkcs8"),
                "message should point at openssl conversion: " + e.getMessage());
    }

    // ---- helpers ------------------------------------------------------

    private static Path resource(String name) {
        var u = PemLoaderTest.class.getClassLoader().getResource(name);
        if (u == null) throw new IllegalStateException("test resource missing: " + name);
        try { return Paths.get(u.toURI()); }
        catch (URISyntaxException e) { throw new IllegalStateException(e); }
    }

    // Silence unused-import warnings.
    @SuppressWarnings("unused")
    private static final Class<?> UNUSED_CHARSETS = StandardCharsets.class;
}
