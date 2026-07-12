/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.wire.messages;

import io.mdudel.zenoh.purejava.wire.Extension;
import io.mdudel.zenoh.purejava.wire.WBuf;
import io.mdudel.zenoh.purejava.wire.WhatAmI;
import io.mdudel.zenoh.purejava.wire.WhatAmIMatcher;
import io.mdudel.zenoh.purejava.wire.ZenohId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScoutTest {

    @Test void encodeMinimalNoZidNoExtensions() {
        // any-role matcher, version 9, no ZID, no ext
        Scout s = new Scout(9, WhatAmIMatcher.any());
        byte[] out = s.encode();
        // header: id=0x01 | Z(0)=0 -> 0x01
        assertEquals(0x01, out[0] & 0xFF);
        // version
        assertEquals(9, out[1] & 0xFF);
        // flags: zid_len=0 (nibble), I=0, what=0b111 -> 0x07
        assertEquals(0x07, out[2] & 0xFF);
        assertEquals(3, out.length);
    }

    @Test void encodeWithZidSetsIAndPacksNibble() {
        ZenohId zid = new ZenohId(new byte[]{
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10
        });
        Scout s = new Scout(9, WhatAmIMatcher.of(WhatAmI.ROUTER), zid, List.of());
        byte[] out = s.encode();
        assertEquals(0x01, out[0] & 0xFF);              // header
        assertEquals(9,    out[1] & 0xFF);              // version
        // flags: nibble=15 (<<4=0xF0), I=0x08, what=0b001 -> 0xF9
        assertEquals(0xF9, out[2] & 0xFF);
        // then 16 zid bytes
        for (int i = 0; i < 16; i++) {
            assertEquals(zid.bytes()[i], out[3 + i]);
        }
        assertEquals(3 + 16, out.length);
    }

    @Test void encodeWithExtensionsSetsZFlag() {
        Scout s = new Scout(1, WhatAmIMatcher.of(WhatAmI.PEER), null,
                List.of(Extension.z64(3, false, 42L)));
        byte[] out = s.encode();
        assertEquals(0x81, out[0] & 0xFF);   // Z=1 -> 0x80 | 0x01
        assertEquals(1,    out[1] & 0xFF);   // version
        assertEquals(0x02, out[2] & 0xFF);   // flags: nibble=0, I=0, what=0b010
        assertEquals(0x23, out[3] & 0xFF);   // ext header: id=3, ENC=Z64(0x20) -> 0x23
        assertEquals(0x2A, out[4] & 0xFF);   // varint(42) = 0x2A
        assertEquals(5, out.length);
    }

    @Test void encodeDecodeRoundtripWithZid() {
        ZenohId zid = new ZenohId(new byte[]{(byte)0xAA, (byte)0xBB, (byte)0xCC});
        Scout original = new Scout(9, WhatAmIMatcher.any(), zid, List.of());
        Scout decoded  = Scout.decode(original.encode());
        assertEquals(9, decoded.version());
        assertEquals(WhatAmIMatcher.any(), decoded.what());
        assertTrue(decoded.zid().isPresent());
        assertEquals(zid, decoded.zid().get());
        assertTrue(decoded.extensions().isEmpty());
    }

    @Test void encodeDecodeRoundtripWithoutZid() {
        Scout original = new Scout(9, WhatAmIMatcher.of(WhatAmI.CLIENT));
        Scout decoded  = Scout.decode(original.encode());
        assertEquals(9, decoded.version());
        assertTrue(decoded.what().matchesClient());
        assertFalse(decoded.what().matchesRouter());
        assertFalse(decoded.zid().isPresent());
    }

    @Test void decodeRejectsWrongId() {
        // Header 0x02 = HELLO id, followed by valid-looking bytes
        byte[] bogus = new byte[]{0x02, 9, 0x07};
        assertThrows(IllegalArgumentException.class, () -> Scout.decode(bogus));
    }

    @Test void decodeRejectsAllReservedRoleBits() {
        // flags with only the reserved bits (masked away leaves 0 roles)
        byte[] bogus = new byte[]{0x01, 9, 0x00};       // what=0b000
        assertThrows(IllegalArgumentException.class, () -> Scout.decode(bogus));
    }

    @Test void decodeSampleFromReferenceLayout() {
        // Hand-craft a SCOUT: id=0x01, no Z, no I, what=Router(0b001)
        WBuf w = new WBuf();
        w.u8(0x01).u8(9).u8(0x01);
        Scout decoded = Scout.decode(w.toByteArray());
        assertEquals(9, decoded.version());
        assertEquals(EnumSet(WhatAmI.ROUTER), decoded.what().roles());
    }

    private static java.util.EnumSet<WhatAmI> EnumSet(WhatAmI... roles) {
        java.util.EnumSet<WhatAmI> s = java.util.EnumSet.noneOf(WhatAmI.class);
        for (WhatAmI r : roles) s.add(r);
        return s;
    }
}
