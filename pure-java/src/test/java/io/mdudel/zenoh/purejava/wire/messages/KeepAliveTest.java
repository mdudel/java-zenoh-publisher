/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.wire.messages;

import io.mdudel.zenoh.purejava.wire.Extension;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KeepAliveTest {

    @Test void emptySingletonIsSingleByte() {
        byte[] out = KeepAlive.EMPTY.encode();
        assertEquals(1,    out.length);
        // header: id=0x04, Z=0 -> 0x04
        assertEquals(0x04, out[0] & 0xFF);
    }

    @Test void newEmptyIsIdenticalToSingleton() {
        assertArrayEquals(KeepAlive.EMPTY.encode(),
                          new KeepAlive(List.of()).encode());
    }

    @Test void withExtensionsSetsZFlag() {
        KeepAlive ka = new KeepAlive(List.of(Extension.z64(1, false, 7L)));
        byte[] out = ka.encode();
        // header: id=0x04 | Z=0x80 = 0x84
        assertEquals(0x84, out[0] & 0xFF);
        // ext: id=1 Z64 M=0 Z=0 -> 0x21
        assertEquals(0x21, out[1] & 0xFF);
        // varint(7) -> 0x07
        assertEquals(0x07, out[2] & 0xFF);
        assertEquals(3,    out.length);
    }

    @Test void decodeEmpty() {
        KeepAlive ka = KeepAlive.decode(new byte[]{0x04});
        assertTrue(ka.extensions().isEmpty());
    }

    @Test void decodeWithExtensions() {
        KeepAlive ka = KeepAlive.decode(new byte[]{(byte) 0x84, 0x01});
        // 0x84 = KALIVE | Z, then extension 0x01 = id=1 UNIT M=0 Z=0
        assertEquals(1, ka.extensions().size());
        assertEquals(1, ka.extensions().get(0).id());
        assertEquals(Extension.Encoding.UNIT, ka.extensions().get(0).encoding());
    }

    @Test void decodeRejectsWrongId() {
        // 0x03 = CLOSE - not a KeepAlive
        assertThrows(IllegalArgumentException.class,
                () -> KeepAlive.decode(new byte[]{0x03}));
    }

    @Test void roundtrip() {
        KeepAlive in  = new KeepAlive(List.of(
                Extension.unit(0, false),
                Extension.zbuf(2, true, new byte[]{0x11})));
        KeepAlive out = KeepAlive.decode(in.encode());
        assertEquals(in, out);
    }
}
