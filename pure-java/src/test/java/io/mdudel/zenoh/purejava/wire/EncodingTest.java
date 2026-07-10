/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.wire;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncodingTest {

    @Test void emptyEncodesAsSingleZeroByte() {
        // composite = (0 << 1) | 0 = 0 → varint = 0x00
        WBuf w = new WBuf();
        Encoding.EMPTY.encode(w);
        assertArrayEquals(new byte[] { 0x00 }, w.toByteArray());
    }

    @Test void bareIdEncodesShiftedLeftBy1() {
        // id=5, no schema: composite = 10 = 0x0A
        WBuf w = new WBuf();
        Encoding.of(5).encode(w);
        assertArrayEquals(new byte[] { 0x0A }, w.toByteArray());
    }

    @Test void idWithSchemaSetsSFlagAndEncodesSchemaBytes() {
        // id=5, schema="ab" → composite = (5<<1) | 1 = 11 = 0x0B, then len=2, then 'a','b'
        WBuf w = new WBuf();
        Encoding.of(5, "ab").encode(w);
        assertArrayEquals(new byte[] { 0x0B, 0x02, 'a', 'b' }, w.toByteArray());
    }

    @Test void largeIdVarintsCorrectly() {
        // id=0x7FFF (u16 max halved so composite fits in 16 bits): composite = 0xFFFE
        // varint encoding of 0xFFFE is [0xFE, 0xFF, 0x03] (two bytes 7-bit chunks + 2-bit MSB)
        WBuf w = new WBuf();
        Encoding.of(0x7FFF).encode(w);
        byte[] out = w.toByteArray();
        assertEquals(3, out.length);
        assertEquals((byte) 0xFE, out[0]);
        assertEquals((byte) 0xFF, out[1]);
        assertEquals((byte) 0x03, out[2]);
    }

    @Test void roundTripEmpty() {
        WBuf w = new WBuf();
        Encoding.EMPTY.encode(w);
        Encoding back = Encoding.decode(new RBuf(w.toByteArray()));
        assertTrue(back.isEmpty());
        assertEquals(0, back.id());
        assertFalse(back.hasSchema());
        assertNull(back.schema());
    }

    @Test void roundTripIdOnly() {
        WBuf w = new WBuf();
        Encoding.of(Encoding.ID_APPLICATION_JSON).encode(w);
        Encoding back = Encoding.decode(new RBuf(w.toByteArray()));
        assertEquals(Encoding.ID_APPLICATION_JSON, back.id());
        assertFalse(back.hasSchema());
    }

    @Test void roundTripWithSchema() {
        Encoding orig = Encoding.of(Encoding.ID_TEXT_PLAIN, "charset=utf-8");
        WBuf w = new WBuf();
        orig.encode(w);
        Encoding back = Encoding.decode(new RBuf(w.toByteArray()));
        assertEquals(orig, back);
        assertEquals("charset=utf-8", back.schemaAsString());
    }

    @Test void rejectsIdOutOfU16Range() {
        assertThrows(IllegalArgumentException.class, () -> Encoding.of(0x10000));
        assertThrows(IllegalArgumentException.class, () -> Encoding.of(-1));
    }

    @Test void rejectsSchemaOver255Bytes() {
        byte[] tooBig = new byte[256];
        assertThrows(IllegalArgumentException.class,
                () -> Encoding.of(1, tooBig));
    }

    @Test void schemaAtMaximumLength255Works() {
        byte[] justFits = new byte[255];
        for (int i = 0; i < 255; i++) justFits[i] = (byte) (i & 0xFF);
        Encoding e = Encoding.of(1, justFits);
        WBuf w = new WBuf();
        e.encode(w);
        // header byte, then u8 length 255, then 255 bytes = 257 bytes total (id=1 fits in 1 byte)
        byte[] out = w.toByteArray();
        assertEquals(1 + 1 + 255, out.length);
        assertEquals((byte) 0xFF, out[1]);
        Encoding back = Encoding.decode(new RBuf(out));
        assertArrayEquals(justFits, back.schema());
    }

    @Test void utf8SchemaRoundtripsExactly() {
        String s = "application/vnd.mdudel+cbor; version=1";
        Encoding e = Encoding.of(42, s);
        WBuf w = new WBuf();
        e.encode(w);
        Encoding back = Encoding.decode(new RBuf(w.toByteArray()));
        assertEquals(s, back.schemaAsString());
        assertArrayEquals(s.getBytes(StandardCharsets.UTF_8), back.schema());
    }
}
