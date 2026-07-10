/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.wire.messages;

import io.mdudel.zenoh.purejava.wire.Extension;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PushTest {

    @Test void headerLayoutForOfPutStandardShape() {
        // ofPut("k", bytes) → N=1, M=0, Z=0. header = 0x1d | 0x20 = 0x3D
        Put put = Put.ofBytes(new byte[] { 'X' });
        Push p = Push.ofPut("k", put);
        byte[] out = p.encode();
        // header, keyScope varint (0 → 0x00), N-flag ⇒ lenString ("k" 1B → [0x01,'k']),
        // then body = Put encode = [0x01, 0x01, 'X']
        assertArrayEquals(new byte[] {
                0x3D,             // 0x1d | N
                0x00,             // keyScope=0
                0x01, 'k',        // lenBytes suffix
                0x01, 0x01, 'X'   // Put body
        }, out);
    }

    @Test void mapOnlyPushHasNoSuffixAndClearsN() {
        // Construct explicitly with keySuffix=null: N flag must be 0.
        Push p = new Push(7, null, false, null, new byte[] { 'q' });
        byte[] out = p.encode();
        assertEquals((byte) 0x1D, out[0]);   // no N, no M, no Z
        assertEquals((byte) 0x07, out[1]);   // scope=7
        assertEquals((byte) 'q',  out[2]);   // body starts immediately
    }

    @Test void senderMappingSetsMFlag() {
        Push p = new Push(3, null, true, null, new byte[] { 'x' });
        assertEquals((byte) 0x5D, p.encode()[0]);  // 0x1d | M(0x40)
    }

    @Test void extensionsSetZFlag() {
        Push p = new Push(0, "k", false,
                List.of(Extension.unit(1, false)),
                new byte[] { 'x' });
        byte[] out = p.encode();
        // 0x1d | N(0x20) | Z(0x80) = 0xBD
        assertEquals((byte) 0xBD, out[0]);
    }

    @Test void bigScopeVarints() {
        // scope = 0xFFFF = varint [0xFF, 0xFF, 0x03]
        Push p = new Push(0xFFFF, "kk", false, null, new byte[] { 'q' });
        byte[] out = p.encode();
        assertEquals((byte) 0x3D, out[0]);           // N flag set
        assertEquals((byte) 0xFF, out[1]);
        assertEquals((byte) 0xFF, out[2]);
        assertEquals((byte) 0x03, out[3]);
        assertEquals((byte) 0x02, out[4]);           // suffix len
        assertEquals((byte) 'k',  out[5]);
        assertEquals((byte) 'k',  out[6]);
        assertEquals((byte) 'q',  out[7]);
    }

    @Test void roundtripStandardShape() {
        Put put = Put.ofString("hello");
        Push orig = Push.ofPut("my/key/path", put);
        Push back = Push.decode(orig.encode());
        assertEquals(0, back.keyScope());
        assertEquals("my/key/path", back.keySuffix());
        assertFalse(back.senderMapping());
        assertTrue(back.extensions().isEmpty());

        // The body should decode back into an equivalent Put
        Put backPut = Put.decode(back.body());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), backPut.payload());
    }

    @Test void roundtripMappingOnly() {
        Push orig = new Push(42, null, true,
                List.of(Extension.z64(3, false, 999L)),
                new byte[] { 1, 2, 3 });
        Push back = Push.decode(orig.encode());
        assertEquals(42, back.keyScope());
        assertNull(back.keySuffix());
        assertTrue(back.senderMapping());
        assertEquals(1, back.extensions().size());
        assertArrayEquals(new byte[] { 1, 2, 3 }, back.body());
    }

    @Test void rejectsWrongId() {
        byte[] bad = new byte[] { 0x1c, 0x00 };  // REQUEST, not PUSH
        assertThrows(IllegalArgumentException.class, () -> Push.decode(bad));
    }

    @Test void rejectsEmptyKeyExprInHelper() {
        assertThrows(IllegalArgumentException.class,
                () -> Push.ofPut("", Put.ofBytes(new byte[] { 1 })));
        assertThrows(IllegalArgumentException.class,
                () -> Push.ofPut(null, Put.ofBytes(new byte[] { 1 })));
    }

    @Test void rejectsScopeOutOfU16Range() {
        assertThrows(IllegalArgumentException.class,
                () -> new Push(0x10000, "k", false, null, new byte[0]));
    }

    @Test void utf8SuffixRoundtrips() {
        String key = "org/dev/tracks/ünïcode";
        Push orig = Push.ofPut(key, Put.ofString("v"));
        Push back = Push.decode(orig.encode());
        assertEquals(key, back.keySuffix());
    }
}
