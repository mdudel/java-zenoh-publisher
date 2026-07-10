/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.wire.messages;

import io.mdudel.zenoh.purejava.wire.Extension;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeclareTest {

    @Test void headerLayoutForBareDeclareSubscriber() {
        // ofDeclareSubscriber(id=1, key="k")
        //   Declare header = 0x1e (no I, no Z)
        //   DeclareSubscriber body header = 0x22 (N flag)
        //   subs_id varint = 0x01
        //   scope varint   = 0x00
        //   len suffix "k" = 0x01, 'k'
        Declare d = Declare.ofDeclareSubscriber(DeclareSubscriber.ofKeyExpr(1L, "k"));
        assertArrayEquals(
                new byte[] { 0x1e, 0x22, 0x01, 0x00, 0x01, 'k' },
                d.encode());
    }

    @Test void headerLayoutForBareUndeclareSubscriber() {
        Declare d = Declare.ofUndeclareSubscriber(UndeclareSubscriber.of(1L));
        assertArrayEquals(
                new byte[] { 0x1e, 0x03, 0x01 },
                d.encode());
    }

    @Test void interestIdSetsIFlag() {
        Declare d = new Declare(
                42L, java.util.List.of(),
                Declare.Body.of(DeclareSubscriber.ofKeyExpr(1L, "k")));
        byte[] out = d.encode();
        assertEquals((byte) 0x3e, out[0]);   // 0x1e | I(0x20)
        assertEquals((byte) 0x2A, out[1]);   // interest_id varint = 42
    }

    @Test void extensionsSetZFlag() {
        Declare d = new Declare(
                null,
                java.util.List.of(Extension.z64(1, false, 42L)),
                Declare.Body.of(DeclareSubscriber.ofKeyExpr(1L, "k")));
        assertEquals((byte) 0x9e, d.encode()[0]);   // 0x1e | Z(0x80)
    }

    @Test void bothIAndZ() {
        Declare d = new Declare(
                42L,
                java.util.List.of(Extension.unit(1, false)),
                Declare.Body.of(UndeclareSubscriber.of(9L)));
        assertEquals((byte) 0xbe, d.encode()[0]);   // 0x1e | I | Z
    }

    @Test void roundtripDeclareSubscriber() {
        Declare orig = Declare.ofDeclareSubscriber(
                DeclareSubscriber.ofKeyExpr(0xDEAD_BEEFL, "some/path/here"));
        Declare back = Declare.decode(orig.encode());
        assertEquals(orig.body().kind(), back.body().kind());
        DeclareSubscriber ds = back.body().asDeclareSubscriber();
        assertEquals(0xDEAD_BEEFL, ds.id());
        assertEquals("some/path/here", ds.keySuffix());
        assertNull(back.interestId());
    }

    @Test void roundtripUndeclareSubscriber() {
        Declare orig = Declare.ofUndeclareSubscriber(UndeclareSubscriber.of(0x1000L));
        Declare back = Declare.decode(orig.encode());
        assertEquals(Declare.Body.BodyKind.UNDECLARE_SUBSCRIBER, back.body().kind());
        assertEquals(0x1000L, back.body().asUndeclareSubscriber().id());
    }

    @Test void roundtripWithInterestIdAndExtensions() {
        Declare orig = new Declare(
                99L,
                java.util.List.of(
                        Extension.z64(1, false, 0xAAL),
                        Extension.zbuf(2, false, new byte[] { 'x', 'y' })),
                Declare.Body.of(DeclareSubscriber.ofKeyExpr(1L, "k")));
        Declare back = Declare.decode(orig.encode());
        assertEquals(99L, back.interestId());
        assertEquals(2, back.extensions().size());
        assertEquals(1L, back.body().asDeclareSubscriber().id());
        assertEquals("k", back.body().asDeclareSubscriber().keySuffix());
    }

    @Test void rawBodyRoundtripsUnknownSubType() {
        // Craft a Declare with a body whose sub-id nibble is 0x1A (D_FINAL).
        // Our impl doesn't know D_FINAL as a typed body but must round-trip
        // it as RAW so forwards-compat isn't broken.
        Declare orig = new Declare(null, java.util.List.of(),
                Declare.Body.ofRaw(new byte[] { 0x1A }));   // just the header byte
        byte[] wire = orig.encode();
        // Declare header (0x1e), then raw body byte (0x1A)
        assertArrayEquals(new byte[] { 0x1e, 0x1A }, wire);
        Declare back = Declare.decode(wire);
        assertEquals(Declare.Body.BodyKind.RAW, back.body().kind());
        assertArrayEquals(new byte[] { 0x1A }, back.body().rawBytes());
    }

    @Test void wrongIdRejected() {
        // 0x1d = PUSH, not DECLARE
        assertThrows(IllegalArgumentException.class,
                () -> Declare.decode(new byte[] { 0x1d, 0x00 }));
    }

    @Test void bodyRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> new Declare(null, java.util.List.of(), null));
    }

    @Test void interestIdOverflowRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Declare(1L << 33, java.util.List.of(),
                        Declare.Body.of(UndeclareSubscriber.of(1L))));
    }

    @Test void bodyKindAccessorsMatchStoredValue() {
        // asDeclareSubscriber() on an UndeclareSubscriber body returns null (not a throw).
        Declare d = Declare.ofUndeclareSubscriber(UndeclareSubscriber.of(1L));
        assertNull(d.body().asDeclareSubscriber());
        assertNull(d.body().rawBytes());
        assertTrue(d.body().asUndeclareSubscriber() != null);
    }
}
