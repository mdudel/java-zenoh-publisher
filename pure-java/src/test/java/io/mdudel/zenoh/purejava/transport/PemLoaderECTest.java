/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.transport;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests covering the {@link PemLoader} EC (PKCS#8 and RFC 5915 SEC1)
 * and RSA private/public key decoders.
 */
public class PemLoaderECTest {

    @Test
    void decodesPkcs8EcSecp256r1() {
        assertTrue(runEc("PKCS#8 EC secp256r1", "secp256r1", true));
    }

    @Test
    void decodesSec1Rfc5915EcSecp256r1() {
        assertTrue(runEc("PKCS#1 EC secp256r1", "secp256r1", false));
    }

    @Test
    void decodesSec1Rfc5915EcSecp384r1() {
        assertTrue(runEc("PKCS#1 EC secp384r1", "secp384r1", false));
    }

    @Test
    void decodesPkcs8RsaAndPublicKey() throws Exception {
        assertTrue(runRsa());
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        Path pubPath = Files.createTempFile("pl-rsa-pub-", ".pem");
        try {
            writePem(pubPath, "PUBLIC KEY", kp.getPublic().getEncoded());
            PublicKey pub = PemLoader.readPublicKey(pubPath);
            assertEquals("RSA", pub.getAlgorithm());
        } finally {
            Files.deleteIfExists(pubPath);
        }
    }

    public static void main(String[] args) throws Exception {
        int pass = 0, fail = 0;

        if (runEc("PKCS#8 EC secp256r1", "secp256r1", true))  pass++; else fail++;
        if (runEc("PKCS#1 EC secp256r1", "secp256r1", false)) pass++; else fail++;
        if (runEc("PKCS#1 EC secp384r1", "secp384r1", false)) pass++; else fail++;
        if (runRsa())                                          pass++; else fail++;

        System.out.println("  Results: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static boolean runEc(String name, String curve, boolean pkcs8) {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec(curve));
            KeyPair kp = kpg.generateKeyPair();
            byte[] p8 = kp.getPrivate().getEncoded();
            byte[] blob = pkcs8 ? p8 : buildRfc5915(p8);
            String label = pkcs8 ? "PRIVATE KEY" : "EC PRIVATE KEY";
            Path p = Files.createTempFile("pl-", ".pem");
            try {
                writePem(p, label, blob);
                PrivateKey k = PemLoader.readPrivateKey(p);
                return "EC".equals(k.getAlgorithm());
            } finally {
                Files.deleteIfExists(p);
            }
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean runRsa() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();
            byte[] p8 = kp.getPrivate().getEncoded();
            Path p = Files.createTempFile("pl-rsa-", ".pem");
            try {
                writePem(p, "PRIVATE KEY", p8);
                PrivateKey k = PemLoader.readPrivateKey(p);
                return "RSA".equals(k.getAlgorithm());
            } finally {
                Files.deleteIfExists(p);
            }
        } catch (Throwable t) {
            return false;
        }
    }

    static void writePem(Path p, String label, byte[] der) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN ").append(label).append("-----\n");
        String b64 = Base64.getEncoder().encodeToString(der);
        for (int i = 0; i < b64.length(); i += 64) {
            sb.append(b64, i, Math.min(i + 64, b64.length())).append('\n');
        }
        sb.append("-----END ").append(label).append("-----\n");
        Files.writeString(p, sb.toString());
    }

    static byte[] buildRfc5915(byte[] pkcs8) throws Exception {
        int p = 0;
        p++; readLen(pkcs8, p); p += lenSize(pkcs8, p);
        p++; int vLen = readLen(pkcs8, p); p += lenSize(pkcs8, p) + vLen;
        p++; int algLen = readLen(pkcs8, p); p += lenSize(pkcs8, p);
        int algEnd = p + algLen;
        p++; int oid1Len = readLen(pkcs8, p); p += lenSize(pkcs8, p) + oid1Len;
        if ((pkcs8[p] & 0xff) != 0x06) throw new IllegalStateException("no curve OID");
        p++; int curveLen = readLen(pkcs8, p); p += lenSize(pkcs8, p);
        byte[] curveOid = new byte[curveLen];
        System.arraycopy(pkcs8, p, curveOid, 0, curveLen);
        p = algEnd;
        p++; int kLen = readLen(pkcs8, p); p += lenSize(pkcs8, p);
        byte[] innerEC = new byte[kLen];
        System.arraycopy(pkcs8, p, innerEC, 0, kLen);

        int ip = 0;
        ip++; int innerContentLen = readLen(innerEC, ip); ip += lenSize(innerEC, ip);
        int walk = ip;
        boolean hasCurve = false;
        while (walk < ip + innerContentLen) {
            int tag = innerEC[walk] & 0xff;
            int fLen = readLen(innerEC, walk + 1);
            int fHdr = 1 + lenSize(innerEC, walk + 1);
            if (tag == 0xA0) { hasCurve = true; break; }
            walk += fHdr + fLen;
        }
        if (hasCurve) return innerEC;

        ByteArrayOutputStream tag0Body = new ByteArrayOutputStream();
        tag0Body.write(0x06);
        writeLen(tag0Body, curveLen);
        tag0Body.write(curveOid);
        ByteArrayOutputStream tag0 = new ByteArrayOutputStream();
        tag0.write(0xA0);
        writeLen(tag0, tag0Body.size());
        tag0Body.writeTo(tag0);

        int ip2 = ip;
        ip2++; int vL2 = readLen(innerEC, ip2); int vH = lenSize(innerEC, ip2); ip2 += vH;
        byte[] versionField = new byte[1 + vH + vL2];
        System.arraycopy(innerEC, ip, versionField, 0, versionField.length);
        ip2 += vL2;
        int oL = readLen(innerEC, ip2 + 1); int oH = lenSize(innerEC, ip2 + 1);
        byte[] keyField = new byte[1 + oH + oL];
        System.arraycopy(innerEC, ip2, keyField, 0, keyField.length);

        ByteArrayOutputStream newInner = new ByteArrayOutputStream();
        newInner.write(versionField);
        newInner.write(keyField);
        tag0.writeTo(newInner);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x30);
        writeLen(out, newInner.size());
        newInner.writeTo(out);
        return out.toByteArray();
    }

    static int readLen(byte[] b, int off) {
        int first = b[off] & 0xff;
        if ((first & 0x80) == 0) return first;
        int n = first & 0x7f, len = 0;
        for (int i = 1; i <= n; i++) len = (len << 8) | (b[off + i] & 0xff);
        return len;
    }

    static int lenSize(byte[] b, int off) {
        int first = b[off] & 0xff;
        return (first & 0x80) == 0 ? 1 : 1 + (first & 0x7f);
    }

    static void writeLen(ByteArrayOutputStream o, int len) {
        if (len < 0x80) o.write(len);
        else if (len <= 0xff) { o.write(0x81); o.write(len); }
        else { o.write(0x82); o.write((len >>> 8) & 0xff); o.write(len & 0xff); }
    }
}
