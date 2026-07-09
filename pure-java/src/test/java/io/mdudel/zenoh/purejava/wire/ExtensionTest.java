/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.wire;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionTest {

    // --- header-bit sanity -----------------------------------------------

    @Test void headerBitsMatchSpec() {
        // From reference: FLAG_Z=0x80, ENC_Z64=0x20, FLAG_M=0x10, ID_MASK=0x0F
        assertEquals(0x80, Extension.FLAG_Z);
        assertEquals(0x40, Extension.ENC_ZBUF);
        assertEquals(0x20, Extension.ENC_Z64);
        assertEquals(0x00, Extension.ENC_UNIT);
        assertEquals(0x10, Extension.FLAG_M);
        assertEquals(0x0F, Extension.ID_MASK);
    }

    // --- single-extension roundtrips ------------------------------------

    @Test void unitExtensionSingle() {
        List<Extension> in = List.of(Extension.unit(5, false));
        WBuf w = new WBuf();
        Extension.writeAll(in, w);

        // Header only, Z=0 (last), id=5, enc=UNIT, M=0 -> 0x05
        byte[] bytes = w.toByteArray();
        assertEquals(1, bytes.length);
        assertEquals(0x05, bytes[0] & 0xFF);

        List<Extension> out = Extension.readAll(new RBuf(bytes));
        assertEquals(in, out);
    }

    @Test void z64ExtensionSingle() {
        List<Extension> in = List.of(Extension.z64(3, true, 300L));
        WBuf w = new WBuf();
        Extension.writeAll(in, w);
        byte[] bytes = w.toByteArray();

        // header: id=3 | M=0x10 | ENC_Z64=0x20 | Z=0 = 0x33
        assertEquals(0x33, bytes[0] & 0xFF);
        // then varint(300) = AC 02
        assertEquals(0xAC, bytes[1] & 0xFF);
        assertEquals(0x02, bytes[2] & 0xFF);

        List<Extension> out = Extension.readAll(new RBuf(bytes));
        assertEquals(in, out);
        assertEquals(300L, out.get(0).asZ64());
        assertTrue(out.get(0).mandatory());
    }

    @Test void zbufExtensionSingle() {
        byte[] payload = {0x11, 0x22, 0x33};
        List<Extension> in = List.of(Extension.zbuf(7, false, payload));
        WBuf w = new WBuf();
        Extension.writeAll(in, w);
        byte[] bytes = w.toByteArray();

        // header: id=7 | ENC_ZBUF=0x40 | M=0 | Z=0 = 0x47
        assertEquals(0x47, bytes[0] & 0xFF);
        // then varint(3) = 03, then 11 22 33
        assertEquals(0x03, bytes[1] & 0xFF);
        assertEquals(0x11, bytes[2] & 0xFF);
        assertEquals(0x22, bytes[3] & 0xFF);
        assertEquals(0x33, bytes[4] & 0xFF);

        List<Extension> out = Extension.readAll(new RBuf(bytes));
        assertEquals(in, out);
        assertArrayEquals(payload, out.get(0).asZBuf());
    }

    // --- chains ---------------------------------------------------------

    @Test void chainOfThreeSetsZFlagOnAllButLast() {
        List<Extension> in = List.of(
                Extension.unit(1, false),
                Extension.z64(2, true, 42L),
                Extension.zbuf(3, false, new byte[]{(byte) 0xFF}));
        WBuf w = new WBuf();
        Extension.writeAll(in, w);
        byte[] bytes = w.toByteArray();

        // first header: id=1 UNIT M=0 Z=1 -> 0x81
        assertEquals(0x81, bytes[0] & 0xFF);
        // second header: id=2 Z64 M=1 Z=1 -> 0xB2  (0x80 | 0x20 | 0x10 | 0x02)
        // (position 1 in bytes)
        // then varint(42)=0x2A at position 2
        assertEquals(0xB2, bytes[1] & 0xFF);
        assertEquals(0x2A, bytes[2] & 0xFF);
        // third header: id=3 ZBUF M=0 Z=0 -> 0x43
        assertEquals(0x43, bytes[3] & 0xFF);
        // then varint(1) len + one byte 0xFF
        assertEquals(0x01, bytes[4] & 0xFF);
        assertEquals(0xFF, bytes[5] & 0xFF);

        List<Extension> out = Extension.readAll(new RBuf(bytes));
        assertEquals(in, out);
    }

    // --- unknown-extension parsing ---------------------------------------

    @Test void readerCanSkipUnknownExtensions() {
        // Craft a hand-rolled chain: unknown ZBUF at id=13, then known UNIT at id=0
        WBuf w = new WBuf();
        // unknown zbuf(id=13, M=0, Z=1) header = 0x80 | 0x40 | 0x0D = 0xCD
        w.u8(0xCD).lenBytes(new byte[]{1, 2, 3, 4});
        // known unit(id=0, M=0, Z=0) = 0x00
        w.u8(0x00);

        List<Extension> chain = Extension.readAll(new RBuf(w.toByteArray()));
        assertEquals(2, chain.size());
        assertEquals(13, chain.get(0).id());
        assertEquals(Extension.Encoding.ZBUF, chain.get(0).encoding());
        assertArrayEquals(new byte[]{1, 2, 3, 4}, chain.get(0).asZBuf());
        assertEquals(0, chain.get(1).id());
        assertEquals(Extension.Encoding.UNIT, chain.get(1).encoding());
    }

    // --- validation ------------------------------------------------------

    @Test void reservedEncodingRejected() {
        WBuf w = new WBuf();
        // header with ENC=0b11 -> 0x60
        w.u8(0x60);
        assertThrows(IllegalArgumentException.class,
                () -> Extension.readAll(new RBuf(w.toByteArray())));
    }

    @Test void idOverflowRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Extension.unit(16, false));
        assertThrows(IllegalArgumentException.class,
                () -> Extension.z64(-1, false, 0L));
    }

    @Test void z64NegativeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Extension.z64(1, false, -1L));
    }
}
