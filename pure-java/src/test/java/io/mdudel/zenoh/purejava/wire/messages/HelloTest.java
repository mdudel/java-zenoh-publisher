/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.wire.messages;

import io.mdudel.zenoh.purejava.wire.Extension;
import io.mdudel.zenoh.purejava.wire.WBuf;
import io.mdudel.zenoh.purejava.wire.WhatAmI;
import io.mdudel.zenoh.purejava.wire.ZenohId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HelloTest {

    @Test void encodeMinimalNoLocatorsNoExtensions() {
        ZenohId zid = new ZenohId(new byte[]{(byte)0xAA});
        Hello h = new Hello(9, WhatAmI.ROUTER, zid, List.of(), List.of());
        byte[] out = h.encode();
        // header: id=0x02 | L(0) | Z(0) -> 0x02
        assertEquals(0x02, out[0] & 0xFF);
        // version
        assertEquals(9, out[1] & 0xFF);
        // lenWai: nibble=0 (single-byte ZID), wai=ROUTER(0b00) -> 0x00
        assertEquals(0x00, out[2] & 0xFF);
        // ZID byte
        assertEquals(0xAA, out[3] & 0xFF);
        assertEquals(4, out.length);
    }

    @Test void encodeSetsLFlagAndWritesLocatorList() {
        ZenohId zid = new ZenohId(new byte[]{0x01, 0x02, 0x03});
        Hello h = new Hello(9, WhatAmI.PEER, zid,
                List.of("tcp/192.168.1.10:7447", "tls/host:7447"),
                List.of());
        byte[] out = h.encode();
        // header: id=0x02 | L=0x20 -> 0x22
        assertEquals(0x22, out[0] & 0xFF);
        assertEquals(9, out[1] & 0xFF);
        // lenWai: nibble=2 (3-byte ZID -> 2), wai=PEER(0b01) -> 0x21
        assertEquals(0x21, out[2] & 0xFF);
        // ZID
        assertEquals(0x01, out[3] & 0xFF);
        assertEquals(0x02, out[4] & 0xFF);
        assertEquals(0x03, out[5] & 0xFF);
        // varint locator count = 2 -> 0x02
        assertEquals(0x02, out[6] & 0xFF);
        // z8-length "tcp/192.168.1.10:7447" (21 chars)
        assertEquals(21, out[7] & 0xFF);
        for (int i = 0; i < 21; i++) {
            assertEquals("tcp/192.168.1.10:7447".charAt(i), out[8 + i] & 0xFF);
        }
        // z8-length "tls/host:7447" (13 chars)
        assertEquals(13, out[8 + 21] & 0xFF);
        for (int i = 0; i < 13; i++) {
            assertEquals("tls/host:7447".charAt(i), out[8 + 21 + 1 + i] & 0xFF);
        }
        assertEquals(8 + 21 + 1 + 13, out.length);
    }

    @Test void encodeSetsZFlagWithExtensions() {
        ZenohId zid = new ZenohId(new byte[]{0x42});
        Hello h = new Hello(1, WhatAmI.CLIENT, zid, List.of(),
                List.of(Extension.z64(5, false, 100L)));
        byte[] out = h.encode();
        // header: id=0x02 | Z=0x80 -> 0x82
        assertEquals(0x82, out[0] & 0xFF);
        assertEquals(1,    out[1] & 0xFF);
        assertEquals(0x02, out[2] & 0xFF);   // nibble=0, wai=CLIENT(0b10)
        assertEquals(0x42, out[3] & 0xFF);   // ZID
        // ext: id=5, ENC=Z64(0x20) -> 0x25
        assertEquals(0x25, out[4] & 0xFF);
        // varint(100) = 0x64
        assertEquals(0x64, out[5] & 0xFF);
        assertEquals(6, out.length);
    }

    @Test void decodeReferenceZenohdShape() {
        // Hand-crafted: what a real zenohd might send in multicast.
        //   header 0x22 (L=1, id=0x02)
        //   version 0x09
        //   lenWai: nibble=15 (16-byte ZID), wai=Router(0b00) -> 0xF0
        //   16 ZID bytes
        //   varint(1) locator count
        //   z8(20) "tcp/10.0.0.1:7447"... nope 17 chars -> 0x11
        //   17 UTF-8 bytes
        WBuf w = new WBuf();
        w.u8(0x22).u8(9).u8(0xF0);
        for (int i = 0; i < 16; i++) w.u8(i + 1);
        w.u8(0x01);       // varint count = 1
        String loc = "tcp/10.0.0.1:7447";
        byte[] locBytes = loc.getBytes(StandardCharsets.UTF_8);
        w.u8(locBytes.length).bytes(locBytes);

        Hello h = Hello.decode(w.toByteArray());
        assertEquals(9, h.version());
        assertEquals(WhatAmI.ROUTER, h.whatAmI());
        assertEquals(16, h.zid().length());
        assertEquals(1, h.locators().size());
        assertEquals(loc, h.locators().get(0));
        assertTrue(h.extensions().isEmpty());
    }

    @Test void decodeWithoutLFlagYieldsEmptyLocators() {
        WBuf w = new WBuf();
        w.u8(0x02).u8(9).u8(0x01).u8(0xAA).u8(0xBB);   // 2-byte ZID, PEER
        Hello h = Hello.decode(w.toByteArray());
        assertTrue(h.locators().isEmpty(), "implicit locator = UDP source");
        assertEquals(WhatAmI.PEER, h.whatAmI());
    }

    @Test void encodeDecodeRoundtripComplete() {
        ZenohId zid = new ZenohId(new byte[]{0x11, 0x22, 0x33, 0x44});
        Hello original = new Hello(9, WhatAmI.PEER, zid,
                List.of("tcp/1.2.3.4:7447", "ws/host:8080"),
                List.of(Extension.z64(7, false, 999L)));
        Hello decoded = Hello.decode(original.encode());
        assertEquals(original.version(), decoded.version());
        assertEquals(original.whatAmI(), decoded.whatAmI());
        assertEquals(original.zid(),     decoded.zid());
        assertEquals(original.locators(), decoded.locators());
        assertEquals(1, decoded.extensions().size());
        assertEquals(999L, decoded.extensions().get(0).asZ64());
    }

    @Test void decodeRejectsWrongId() {
        // Header 0x01 = SCOUT id, must not parse as HELLO
        byte[] bogus = new byte[]{0x01, 9, 0x00, 0x00};
        assertThrows(IllegalArgumentException.class, () -> Hello.decode(bogus));
    }

    @Test void encodeRejectsOverLongLocator() {
        ZenohId zid = new ZenohId(new byte[]{0x01});
        String tooLong = "x".repeat(Hello.MAX_LOCATOR_LEN + 1);
        assertThrows(IllegalArgumentException.class, () ->
                new Hello(9, WhatAmI.PEER, zid, List.of(tooLong), List.of()));
    }

    @Test void encodeRejectsNullZid() {
        assertThrows(IllegalArgumentException.class, () ->
                new Hello(9, WhatAmI.PEER, null, List.of(), List.of()));
    }
}
