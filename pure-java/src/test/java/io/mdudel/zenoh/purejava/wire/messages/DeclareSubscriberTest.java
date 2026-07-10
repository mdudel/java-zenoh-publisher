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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeclareSubscriberTest {

    @Test void ofKeyExprEmitsStandardShape() {
        // id=1, scope=0, key="k" (N=1, M=0, Z=0):
        //   header = 0x02 | N(0x20) = 0x22
        //   varint(1)     = 0x01
        //   varint(0)     = 0x00
        //   lenBytes("k") = 0x01, 'k'
        DeclareSubscriber ds = DeclareSubscriber.ofKeyExpr(1L, "k");
        WBuf w = new WBuf();
        ds.encode(w);
        assertArrayEquals(new byte[] { 0x22, 0x01, 0x00, 0x01, 'k' }, w.toByteArray());
    }

    @Test void mappingOnlyClearsNFlag() {
        DeclareSubscriber ds = new DeclareSubscriber(5L, 7, null, false, null);
        WBuf w = new WBuf();
        ds.encode(w);
        byte[] out = w.toByteArray();
        assertEquals((byte) 0x02, out[0]);   // no N, no M, no Z
        assertEquals((byte) 0x05, out[1]);   // id
        assertEquals((byte) 0x07, out[2]);   // scope
        assertEquals(3, out.length);
    }

    @Test void senderMappingSetsMFlag() {
        DeclareSubscriber ds = new DeclareSubscriber(3L, 0, null, true, null);
        WBuf w = new WBuf();
        ds.encode(w);
        assertEquals((byte) 0x42, w.toByteArray()[0]);  // 0x02 | M(0x40)
    }

    @Test void extensionsSetZFlag() {
        DeclareSubscriber ds = new DeclareSubscriber(
                1L, 0, "k", false,
                java.util.List.of(Extension.unit(1, false)));
        WBuf w = new WBuf();
        ds.encode(w);
        // 0x02 | N(0x20) | Z(0x80) = 0xA2
        assertEquals((byte) 0xA2, w.toByteArray()[0]);
    }

    @Test void largeIdVarints() {
        // id=0x1_000_000 = 16777216 = varint [0x80, 0x80, 0x80, 0x08]
        DeclareSubscriber ds = new DeclareSubscriber(16777216L, 0, null, false, null);
        WBuf w = new WBuf();
        ds.encode(w);
        byte[] out = w.toByteArray();
        assertEquals((byte) 0x02, out[0]);
        assertEquals((byte) 0x80, out[1]);
        assertEquals((byte) 0x80, out[2]);
        assertEquals((byte) 0x80, out[3]);
        assertEquals((byte) 0x08, out[4]);
        assertEquals((byte) 0x00, out[5]);   // scope=0
    }

    @Test void roundtripStandardShape() {
        DeclareSubscriber orig = DeclareSubscriber.ofKeyExpr(42L, "demo/hello");
        WBuf w = new WBuf();
        orig.encode(w);
        // Skip the header manually (Declare.decode does that; we mimic here).
        RBuf r = new RBuf(w.toByteArray());
        int header = r.u8();
        DeclareSubscriber back = DeclareSubscriber.decode(header, r);
        assertEquals(42L, back.id());
        assertEquals(0, back.keyScope());
        assertEquals("demo/hello", back.keySuffix());
        assertFalse(back.senderMapping());
        assertTrue(back.extensions().isEmpty());
    }

    @Test void roundtripMappingOnly() {
        DeclareSubscriber orig = new DeclareSubscriber(0xDEAD_BEEFL, 100, null, true, null);
        WBuf w = new WBuf();
        orig.encode(w);
        RBuf r = new RBuf(w.toByteArray());
        int header = r.u8();
        DeclareSubscriber back = DeclareSubscriber.decode(header, r);
        assertEquals(0xDEAD_BEEFL, back.id());
        assertEquals(100, back.keyScope());
        assertNull(back.keySuffix());
        assertTrue(back.senderMapping());
    }

    @Test void utf8SuffixRoundtripsExactly() {
        String key = "org/dev/ünïcode/thing";
        DeclareSubscriber orig = DeclareSubscriber.ofKeyExpr(1L, key);
        WBuf w = new WBuf();
        orig.encode(w);
        RBuf r = new RBuf(w.toByteArray());
        int header = r.u8();
        DeclareSubscriber back = DeclareSubscriber.decode(header, r);
        assertEquals(key, back.keySuffix());
    }

    @Test void rejectsIdOverflow() {
        assertThrows(IllegalArgumentException.class,
                () -> new DeclareSubscriber(-1L, 0, null, false, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DeclareSubscriber(1L << 33, 0, null, false, null));
    }

    @Test void rejectsScopeOutOfU16Range() {
        assertThrows(IllegalArgumentException.class,
                () -> new DeclareSubscriber(1L, 0x10000, "k", false, null));
    }

    @Test void ofKeyExprRejectsEmptyOrNullKey() {
        assertThrows(IllegalArgumentException.class,
                () -> DeclareSubscriber.ofKeyExpr(1L, null));
        assertThrows(IllegalArgumentException.class,
                () -> DeclareSubscriber.ofKeyExpr(1L, ""));
    }

    @Test void decodeRejectsWrongId() {
        // header byte with sub-id nibble != 0x02
        RBuf r = new RBuf(new byte[] { 0x01, 0x00 });
        int header = r.u8();
        assertThrows(IllegalArgumentException.class,
                () -> DeclareSubscriber.decode(header, r));
    }
}
