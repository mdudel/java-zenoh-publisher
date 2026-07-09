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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InitTest {

    // --- InitSyn encode (no extensions) ---------------------------------

    @Test void initSynClientMinimal() {
        ZenohId zid = new ZenohId(new byte[]{
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10
        });
        Init.InitSyn syn = new Init.InitSyn(9, WhatAmI.CLIENT, zid, List.of());
        byte[] out = syn.encode();

        // header: id=0x01, A=0, S=0, Z=0 -> 0x01
        assertEquals(0x01, out[0] & 0xFF);
        // version byte
        assertEquals(9, out[1] & 0xFF);
        // zid_len|x|x|wai byte: nibble=15, wai=CLIENT=0b10 -> 0xF2
        assertEquals(0xF2, out[2] & 0xFF);
        // 16 zid bytes follow verbatim
        for (int i = 0; i < 16; i++) {
            assertEquals(zid.bytes()[i], out[3 + i]);
        }
        // that's it - no S, no A, no Z
        assertEquals(3 + 16, out.length);
    }

    @Test void initSynSetsWaiCorrectly() {
        ZenohId zid = new ZenohId(new byte[]{0x42});
        byte[] router = new Init.InitSyn(1, WhatAmI.ROUTER, zid, List.of()).encode();
        byte[] peer   = new Init.InitSyn(1, WhatAmI.PEER,   zid, List.of()).encode();
        byte[] client = new Init.InitSyn(1, WhatAmI.CLIENT, zid, List.of()).encode();
        // len nibble is 0 (single byte ZID), so lenWai byte holds only wai bits
        assertEquals(0x00, router[2] & 0xFF);
        assertEquals(0x01, peer[2]   & 0xFF);
        assertEquals(0x02, client[2] & 0xFF);
    }

    @Test void initSynWithExtensionsSetsZFlag() {
        ZenohId zid = new ZenohId(new byte[]{0x01});
        Init.InitSyn syn = new Init.InitSyn(
                1, WhatAmI.CLIENT, zid,
                List.of(Extension.z64(2, false, 100L)));
        byte[] out = syn.encode();
        // header now has Z=1 -> 0x01 | 0x80 = 0x81
        assertEquals(0x81, out[0] & 0xFF);
        // version=1, lenWai=0x02, zid=0x01
        assertEquals(0x01, out[1] & 0xFF);
        assertEquals(0x02, out[2] & 0xFF);
        assertEquals(0x01, out[3] & 0xFF);
        // then extension header: id=2 Z64 Z=0 -> 0x22
        assertEquals(0x22, out[4] & 0xFF);
        // then varint(100) = 0x64
        assertEquals(0x64, out[5] & 0xFF);
        assertEquals(6, out.length);
    }

    // --- InitAck decode -------------------------------------------------

    @Test void initAckMinimalRoundtrip() {
        // Hand-craft an InitAck: A=1, S=0, Z=0
        //   header = 0x01 | FLAG_A(0x20) = 0x21
        //   version=1
        //   zid_len nibble=1 (actual=2), wai=ROUTER(0) -> 0x10
        //   zid bytes = {0xAA, 0xBB}
        //   cookie = varint(3) + {0x01,0x02,0x03}
        WBuf w = new WBuf();
        w.u8(0x21).u8(1).u8(0x10).u8(0xAA).u8(0xBB);
        w.lenBytes(new byte[]{0x01, 0x02, 0x03});

        Init.InitAck ack = Init.InitAck.decode(w.toByteArray());
        assertEquals(1, ack.version());
        assertEquals(WhatAmI.ROUTER, ack.whatAmI());
        assertEquals(new ZenohId(new byte[]{(byte) 0xAA, (byte) 0xBB}), ack.zid());
        assertEquals(-1, ack.sniByteOrMinus1());
        assertEquals(-1, ack.batchSizeOrMinus1());
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03}, ack.cookie());
        assertTrue(ack.extensions().isEmpty());
    }

    @Test void initAckWithSizeParamsAndExtensions() {
        // header: id=0x01 | A=0x20 | S=0x40 | Z=0x80 = 0xE1
        WBuf w = new WBuf();
        w.u8(0xE1).u8(9).u8(0x00 | WhatAmI.ROUTER.bits); // lenNibble=0 (1-byte ZID), wai=ROUTER
        w.u8(0xCC);                                       // ZID
        w.u8(0x03);                                       // sni byte (arbitrary)
        w.u16le(65535);                                   // batch size
        w.lenBytes(new byte[]{0x11});                     // cookie
        // one Z64 extension id=5, value=7, not mandatory, Z=0
        w.u8(0x25).u8(0x07);

        Init.InitAck ack = Init.InitAck.decode(w.toByteArray());
        assertEquals(9,     ack.version());
        assertEquals(WhatAmI.ROUTER, ack.whatAmI());
        assertEquals(0x03,  ack.sniByteOrMinus1());
        assertEquals(65535, ack.batchSizeOrMinus1());
        assertArrayEquals(new byte[]{0x11}, ack.cookie());
        assertEquals(1,     ack.extensions().size());
        assertEquals(5,     ack.extensions().get(0).id());
        assertEquals(7L,    ack.extensions().get(0).asZ64());
    }

    @Test void initAckRejectsWrongId() {
        // id=0x02 (OPEN) but with A=1 - decode() must refuse
        byte[] bogus = new byte[]{(byte) 0x22, 1, 0x00, 0x01, 0x00};
        assertThrows(IllegalArgumentException.class, () -> Init.InitAck.decode(bogus));
    }

    @Test void initAckRejectsWithoutAFlag() {
        // Looks like an InitSyn - decode() as InitAck must refuse
        byte[] syn = new byte[]{0x01, 1, 0x00, 0x01};
        assertThrows(IllegalArgumentException.class, () -> Init.InitAck.decode(syn));
    }

    @Test void initSynClientDefaultProducesValidBytes() {
        Init.InitSyn syn = Init.InitSyn.clientDefault(9);
        byte[] bytes = syn.encode();
        // header id
        assertEquals(0x01, bytes[0] & 0xFF);
        // version 9
        assertEquals(9,    bytes[1] & 0xFF);
        // wai=CLIENT low bits
        assertEquals(WhatAmI.CLIENT.bits, bytes[2] & 0x03);
        // 16-byte ZID means lenNibble=15
        assertEquals(15, (bytes[2] >>> 4) & 0x0F);
        // total: 1 header + 1 version + 1 lenWai + 16 ZID = 19
        assertEquals(19, bytes.length);
    }
}
