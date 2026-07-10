/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.wire.messages;

import io.mdudel.zenoh.purejava.wire.Extension;
import io.mdudel.zenoh.purejava.wire.RBuf;
import io.mdudel.zenoh.purejava.wire.WBuf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UndeclareSubscriberTest {

    @Test void ofIdEmitsMinimalBytes() {
        // id=42, no extensions: header=0x03, varint(42)=0x2A
        UndeclareSubscriber us = UndeclareSubscriber.of(42L);
        WBuf w = new WBuf();
        us.encode(w);
        assertArrayEquals(new byte[] { 0x03, 0x2A }, w.toByteArray());
    }

    @Test void extensionsSetZFlag() {
        UndeclareSubscriber us = new UndeclareSubscriber(
                1L, java.util.List.of(Extension.unit(1, false)));
        WBuf w = new WBuf();
        us.encode(w);
        assertEquals((byte) 0x83, w.toByteArray()[0]);   // 0x03 | Z(0x80)
    }

    @Test void largeIdVarints() {
        // id=0xFFFF = varint [0xFF, 0xFF, 0x03]
        UndeclareSubscriber us = UndeclareSubscriber.of(0xFFFFL);
        WBuf w = new WBuf();
        us.encode(w);
        byte[] out = w.toByteArray();
        assertEquals((byte) 0x03, out[0]);
        assertEquals((byte) 0xFF, out[1]);
        assertEquals((byte) 0xFF, out[2]);
        assertEquals((byte) 0x03, out[3]);
        assertEquals(4, out.length);
    }

    @Test void roundtripBare() {
        UndeclareSubscriber orig = UndeclareSubscriber.of(99L);
        WBuf w = new WBuf();
        orig.encode(w);
        RBuf r = new RBuf(w.toByteArray());
        int header = r.u8();
        UndeclareSubscriber back = UndeclareSubscriber.decode(header, r);
        assertEquals(99L, back.id());
        assertTrue(back.extensions().isEmpty());
    }

    @Test void roundtripWithExtensions() {
        UndeclareSubscriber orig = new UndeclareSubscriber(
                1234L,
                java.util.List.of(Extension.z64(1, false, 0xAAL),
                                  Extension.unit(2, true)));
        WBuf w = new WBuf();
        orig.encode(w);
        RBuf r = new RBuf(w.toByteArray());
        int header = r.u8();
        UndeclareSubscriber back = UndeclareSubscriber.decode(header, r);
        assertEquals(1234L, back.id());
        assertEquals(2, back.extensions().size());
    }

    @Test void rejectsIdOverflow() {
        assertThrows(IllegalArgumentException.class,
                () -> new UndeclareSubscriber(-1L, null));
        assertThrows(IllegalArgumentException.class,
                () -> new UndeclareSubscriber(1L << 33, null));
    }

    @Test void decodeRejectsWrongId() {
        RBuf r = new RBuf(new byte[] { 0x02, 0x00 });
        int header = r.u8();
        assertThrows(IllegalArgumentException.class,
                () -> UndeclareSubscriber.decode(header, r));
    }
}
