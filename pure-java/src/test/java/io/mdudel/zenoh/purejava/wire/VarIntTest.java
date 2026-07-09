/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.wire;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the unsigned LEB128 varint codec against the known encoded lengths
 * from the LEB128 spec, plus stream/array API parity and boundary conditions.
 *
 * <p>These tests will remain valid as the wire protocol layers get built on
 * top - any change here means the codec's fundamental contract shifted, and
 * everything above it likely needs to be re-verified.</p>
 */
class VarIntTest {

    // ----- sizeOf --------------------------------------------------------

    @Test void sizeOfKnownBoundaries() {
        assertEquals(1,  VarInt.sizeOf(0L));
        assertEquals(1,  VarInt.sizeOf(1L));
        assertEquals(1,  VarInt.sizeOf(127L));
        assertEquals(2,  VarInt.sizeOf(128L));
        assertEquals(2,  VarInt.sizeOf(16383L));
        assertEquals(3,  VarInt.sizeOf(16384L));
        assertEquals(3,  VarInt.sizeOf(2097151L));
        assertEquals(4,  VarInt.sizeOf(2097152L));
        // Long.MAX_VALUE = 2^63 - 1 fits in 9 LEB128 bytes; the 10-byte hard
        // cap covers full unsigned 64-bit values (with bit 63 set), which
        // Java's signed long can't represent directly but the Zenoh wire
        // spec allows. See VarInt.MAX_BYTES.
        assertEquals(9, VarInt.sizeOf(Long.MAX_VALUE));
    }

    @Test void sizeOfNegativeRejected() {
        assertThrows(IllegalArgumentException.class, () -> VarInt.sizeOf(-1L));
    }

    // ----- encode (array + stream) --------------------------------------

    @Test void encodeZero() {
        assertArrayEquals(new byte[] {0x00}, VarInt.encode(0L));
    }

    @Test void encodeOne() {
        assertArrayEquals(new byte[] {0x01}, VarInt.encode(1L));
    }

    @Test void encode127IsSingleByte() {
        assertArrayEquals(new byte[] {0x7F}, VarInt.encode(127L));
    }

    @Test void encode128IsTwoBytes() {
        // 128 = 0b10000000  ->  low 7 bits = 0, high bit set  ->  0x80
        // remaining          =  0b1        ->  low byte = 0x01
        assertArrayEquals(new byte[] {(byte) 0x80, 0x01}, VarInt.encode(128L));
    }

    @Test void encode300() {
        // 300 = 0b100101100
        // low 7 bits = 0b0101100 = 0x2C, continuation set -> 0xAC
        // next       = 0b10     = 0x02
        assertArrayEquals(new byte[] {(byte) 0xAC, 0x02}, VarInt.encode(300L));
    }

    @Test void encode16383IsTwoBytes() {
        assertArrayEquals(new byte[] {(byte) 0xFF, 0x7F}, VarInt.encode(16383L));
    }

    @Test void encode16384IsThreeBytes() {
        assertArrayEquals(new byte[] {(byte) 0x80, (byte) 0x80, 0x01}, VarInt.encode(16384L));
    }

    @Test void encodeNegativeRejected() {
        assertThrows(IllegalArgumentException.class, () -> VarInt.encode(-1L));
        assertThrows(IllegalArgumentException.class, () -> VarInt.encode(Long.MIN_VALUE));
    }

    @Test void encodeToStreamMatchesArrayVariant() throws IOException {
        long[] values = { 0, 1, 42, 127, 128, 300, 16383, 16384, 1L << 35, Long.MAX_VALUE };
        for (long v : values) {
            byte[] arr = VarInt.encode(v);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int written = VarInt.encode(v, baos);
            assertArrayEquals(arr, baos.toByteArray(),
                    "stream vs array output mismatch for value " + v);
            assertEquals(arr.length, written,
                    "reported written length differs from array length for " + v);
        }
    }

    // ----- decode (array + stream) --------------------------------------

    @Test void roundtripKnownValues() throws IOException {
        long[] values = {
                0L, 1L, 7L, 127L, 128L, 129L, 255L, 300L, 16383L, 16384L,
                1L << 21, 1L << 28, 1L << 35, 1L << 42, 1L << 49, 1L << 56,
                (1L << 63) - 1L, Long.MAX_VALUE
        };
        for (long v : values) {
            // array variant
            byte[] enc = VarInt.encode(v);
            VarInt.Decoded d = VarInt.decode(enc, 0);
            assertEquals(v,           d.value(),     "decoded value mismatch for " + v);
            assertEquals(enc.length,  d.bytesRead(), "decoded length mismatch for " + v);
            // stream variant
            long streamDecoded = VarInt.decode(new ByteArrayInputStream(enc));
            assertEquals(v, streamDecoded, "stream-decoded value mismatch for " + v);
        }
    }

    @Test void decodeAtNonZeroOffset() {
        // Layout: [garbage] [encoded(300) = AC 02] [more garbage]
        byte[] buf = new byte[] {(byte) 0xDE, (byte) 0xAD, (byte) 0xAC, 0x02, (byte) 0xBE, (byte) 0xEF};
        VarInt.Decoded d = VarInt.decode(buf, 2);
        assertEquals(300L, d.value());
        assertEquals(2,    d.bytesRead());
    }

    @Test void decodeTruncatedArrayThrows() {
        // Continuation bit set but no follow-up byte
        assertThrows(IllegalArgumentException.class,
                () -> VarInt.decode(new byte[] {(byte) 0x80}, 0));
    }

    @Test void decodeTruncatedStreamThrowsEof() {
        assertThrows(EOFException.class,
                () -> VarInt.decode(new ByteArrayInputStream(new byte[] {(byte) 0x80})));
    }

    @Test void decodeEmptyStreamThrowsEof() {
        assertThrows(EOFException.class,
                () -> VarInt.decode(new ByteArrayInputStream(new byte[0])));
    }

    @Test void decodeElevenBytePayloadIsMalformed() {
        // 11 continuation bytes = beyond the 10-byte hard cap for 64-bit
        byte[] tooLong = new byte[11];
        for (int i = 0; i < 10; i++) tooLong[i] = (byte) 0x80;
        tooLong[10] = 0x01;
        assertThrows(IllegalArgumentException.class, () -> VarInt.decode(tooLong, 0));
    }
}
