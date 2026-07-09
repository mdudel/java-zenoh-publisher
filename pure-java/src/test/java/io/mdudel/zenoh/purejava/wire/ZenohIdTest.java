/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.wire;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ZenohIdTest {

    @Test void constructRoundtrip() {
        byte[] bytes = new byte[]{1, 2, 3, 4};
        ZenohId id = new ZenohId(bytes);
        assertEquals(4, id.length());
        assertArrayEquals(bytes, id.bytes());
        // defensive copy: mutating input doesn't corrupt the id
        bytes[0] = 99;
        assertEquals(1, id.bytes()[0]);
        // encoded length nibble is length - 1
        assertEquals(3, id.encodedLenNibble());
    }

    @Test void oneByteMinimum() {
        ZenohId id = new ZenohId(new byte[]{42});
        assertEquals(1, id.length());
        assertEquals(0, id.encodedLenNibble());
    }

    @Test void sixteenByteMaximum() {
        byte[] b = new byte[16];
        for (int i = 0; i < 16; i++) b[i] = (byte) i;
        ZenohId id = new ZenohId(b);
        assertEquals(16, id.length());
        assertEquals(15, id.encodedLenNibble());
    }

    @Test void rejectsZeroLength() {
        assertThrows(IllegalArgumentException.class, () -> new ZenohId(new byte[0]));
    }

    @Test void rejects17Bytes() {
        assertThrows(IllegalArgumentException.class, () -> new ZenohId(new byte[17]));
    }

    @Test void randomMatchesLength() {
        for (int n = 1; n <= 16; n++) {
            assertEquals(n, ZenohId.random(n).length());
        }
    }

    @Test void randomDefaultIs16() {
        assertEquals(16, ZenohId.random().length());
    }

    @Test void readAfterLenNibbleRoundtrip() {
        ZenohId original = new ZenohId(new byte[]{0x0A, 0x0B, 0x0C, 0x0D, 0x0E});
        WBuf w = new WBuf();
        // Simulate the wire: this test only cares that readAfterLenNibble
        // consumes exactly length bytes given the correct nibble.
        w.bytes(original.bytes());
        RBuf r = new RBuf(w.toByteArray());
        ZenohId parsed = ZenohId.readAfterLenNibble(original.encodedLenNibble(), r);
        assertEquals(original, parsed);
        assertFalse(r.hasMore(), "cursor should have advanced past the ZID");
    }

    @Test void readAfterLenNibbleRejectsBadNibble() {
        RBuf r = new RBuf(new byte[10]);
        assertThrows(IllegalArgumentException.class, () -> ZenohId.readAfterLenNibble(-1, r));
        assertThrows(IllegalArgumentException.class, () -> ZenohId.readAfterLenNibble(16, r));
    }

    @Test void equalsAndHashCode() {
        ZenohId a = new ZenohId(new byte[]{1, 2, 3});
        ZenohId b = new ZenohId(new byte[]{1, 2, 3});
        ZenohId c = new ZenohId(new byte[]{1, 2, 4});
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test void toStringIsLowercaseHex() {
        ZenohId id = new ZenohId(new byte[]{(byte) 0xAB, (byte) 0xCD, (byte) 0x01});
        assertEquals("abcd01", id.toString());
    }
}
