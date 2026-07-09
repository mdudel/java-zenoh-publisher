/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.wire;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip and boundary tests for the primitive read/write buffers.
 */
class WBufRBufTest {

    @Test void u8Roundtrip() {
        WBuf w = new WBuf();
        w.u8(0).u8(1).u8(127).u8(255);
        RBuf r = new RBuf(w.toByteArray());
        assertEquals(0,   r.u8());
        assertEquals(1,   r.u8());
        assertEquals(127, r.u8());
        assertEquals(255, r.u8());
        assertFalse(r.hasMore());
    }

    @Test void u16LittleEndian() {
        WBuf w = new WBuf();
        w.u16le(0);
        w.u16le(1);
        w.u16le(0x00FF);
        w.u16le(0x0100);
        w.u16le(0xFFFF);
        byte[] bytes = w.toByteArray();
        // 0x0100 encodes as 00 01 (LE)
        assertEquals((byte) 0x00, bytes[6]);
        assertEquals((byte) 0x01, bytes[7]);
        RBuf r = new RBuf(bytes);
        assertEquals(0,      r.u16le());
        assertEquals(1,      r.u16le());
        assertEquals(0x00FF, r.u16le());
        assertEquals(0x0100, r.u16le());
        assertEquals(0xFFFF, r.u16le());
    }

    @Test void varIntBridgesToVarIntClass() {
        WBuf w = new WBuf();
        w.varInt(0).varInt(300).varInt(Long.MAX_VALUE);
        RBuf r = new RBuf(w.toByteArray());
        assertEquals(0L,             r.varInt());
        assertEquals(300L,           r.varInt());
        assertEquals(Long.MAX_VALUE, r.varInt());
    }

    @Test void lenBytesRoundtrip() {
        byte[] payload = {1, 2, 3, 4, 5};
        WBuf w = new WBuf();
        w.lenBytes(new byte[0]).lenBytes(payload);
        RBuf r = new RBuf(w.toByteArray());
        assertArrayEquals(new byte[0], r.lenBytes());
        assertArrayEquals(payload,     r.lenBytes());
    }

    @Test void lenStringUtf8() {
        String s = "hello, world";
        WBuf w = new WBuf();
        w.lenString(s);
        assertEquals(s, new RBuf(w.toByteArray()).lenString());
    }

    @Test void peekDoesNotAdvance() {
        RBuf r = new RBuf(new byte[]{0x2A, 0x2B});
        assertEquals(0x2A, r.peekU8());
        assertEquals(0x2A, r.peekU8());
        assertEquals(2,    r.remaining());
        assertEquals(0x2A, r.u8());
        assertEquals(1,    r.remaining());
    }

    @Test void sliceAdvancesParent() {
        byte[] data = {1, 2, 3, 4, 5, 6};
        RBuf r = new RBuf(data);
        r.u8();                  // consume 1
        RBuf sub = r.slice(3);   // consume 2,3,4
        assertEquals(3, sub.remaining());
        assertEquals(2, sub.u8());
        assertEquals(3, sub.u8());
        assertEquals(4, sub.u8());
        assertEquals(5, r.u8()); // parent has moved past the slice
    }

    @Test void underflowThrows() {
        RBuf r = new RBuf(new byte[]{1});
        assertEquals(1, r.u8());
        assertThrows(IllegalArgumentException.class, r::u8);
        assertThrows(IllegalArgumentException.class, () -> r.u16le());
    }

    @Test void rbufSliceOutOfRangeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new RBuf(new byte[]{1,2,3}, 1, 5));
        assertThrows(IllegalArgumentException.class,
                () -> new RBuf(new byte[]{1,2,3}, -1, 1));
    }
}
