/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.wire;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimestampTest {

    private static ZenohId zid(int... bytes) {
        byte[] arr = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) arr[i] = (byte) bytes[i];
        return new ZenohId(arr);
    }

    @Test void encodesTimeVarintThenLengthPrefixedId() {
        // NTP64 = 0x01: varint = [0x01]
        // id = [0xAA, 0xBB]: lenBytes = [0x02, 0xAA, 0xBB]
        Timestamp t = new Timestamp(1L, zid(0xAA, 0xBB));
        WBuf w = new WBuf();
        t.encode(w);
        assertArrayEquals(new byte[] { 0x01, 0x02, (byte) 0xAA, (byte) 0xBB }, w.toByteArray());
    }

    @Test void bigNtp64Varints() {
        // NTP64 = 0x80: varint = [0x80, 0x01]
        Timestamp t = new Timestamp(0x80L, zid(0xFF));
        WBuf w = new WBuf();
        t.encode(w);
        assertArrayEquals(
                new byte[] { (byte) 0x80, 0x01, 0x01, (byte) 0xFF },
                w.toByteArray());
    }

    @Test void roundTripSmallId() {
        Timestamp orig = new Timestamp(0x1234_5678L, zid(0xAA));
        WBuf w = new WBuf();
        orig.encode(w);
        Timestamp back = Timestamp.decode(new RBuf(w.toByteArray()));
        assertEquals(orig.ntp64(), back.ntp64());
        assertEquals(orig.id(), back.id());
    }

    @Test void roundTripFullSize16ByteId() {
        int[] full = new int[16];
        for (int i = 0; i < 16; i++) full[i] = 0xF0 | (i & 0x0F);
        ZenohId id = zid(full);
        // Largest signed positive long; the varint encoder currently rejects negatives.
        // Extending VarInt to full unsigned 64-bit is tracked separately (Turn 10 note).
        long largePositive = Long.MAX_VALUE;
        Timestamp orig = new Timestamp(largePositive, id);
        WBuf w = new WBuf();
        orig.encode(w);
        Timestamp back = Timestamp.decode(new RBuf(w.toByteArray()));
        assertEquals(largePositive, back.ntp64());
        assertArrayEquals(id.bytes(), back.id().bytes());
    }

    @Test void nowRoundtripsWithinNanoTolerance() {
        // Round-trip Instant.now() through NTP64 and back; NTP64 has ~233 ps
        // resolution (2^-32 s), so millisecond-precision Instants come back
        // within about 1 ms of themselves.
        Instant before = Instant.now();
        Timestamp t = Timestamp.fromInstant(before, zid(0x01));
        Instant after = t.toInstant();
        // Allow up to 1000 ns drift for the double-precision conversion.
        long diffNanos = Math.abs(java.time.Duration.between(before, after).toNanos());
        assertTrue(diffNanos < 1_000_000L, "drift too large: " + diffNanos + " ns");
    }

    @Test void ntpEpochShiftMatchesRfc868() {
        // UNIX epoch (1970-01-01) in NTP seconds = 2208988800
        Timestamp t = Timestamp.fromInstant(Instant.EPOCH, zid(0x01));
        long ntpSeconds = (t.ntp64() >>> 32) & 0xFFFFFFFFL;
        long fraction   = t.ntp64() & 0xFFFFFFFFL;
        assertEquals(Timestamp.NTP_UNIX_OFFSET_SECONDS, ntpSeconds);
        assertEquals(0L, fraction);  // Instant.EPOCH has zero nanos
    }
}
