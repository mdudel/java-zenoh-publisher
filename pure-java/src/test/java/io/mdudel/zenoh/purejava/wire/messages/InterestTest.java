/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.wire.messages;

import io.mdudel.zenoh.purejava.wire.Extension;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterestTest {

    @Test void finalModeBytesAreHeaderPlusId() {
        // Mode=Final (0b00 << 5 = 0x00) | id=0x19 -> header = 0x19
        // interest_id = 42 -> varint = 0x2A
        // No options byte, no key, no ext -> total = 2 bytes.
        Interest i = Interest.finalOf(42L);
        assertArrayEquals(new byte[] { 0x19, 0x2A }, i.encode());
    }

    @Test void currentFutureUnrestrictedFullOptions() {
        // Mode=CurrentFuture (0b11 << 5 = 0x60) | id=0x19 -> header = 0x79
        // interest_id = 1 -> 0x01
        // options = SUBSCRIBERS(0x02) with NO R flag (unrestricted) -> 0x02
        Interest i = new Interest(1L, Interest.Mode.CURRENT_FUTURE,
                Interest.OPT_SUBSCRIBERS, 0, null,
                java.util.List.of());
        assertArrayEquals(new byte[] { 0x79, 0x01, 0x02 }, i.encode());
    }

    @Test void discoverSubscribersEmitsRestrictedNamedShape() {
        // Mode=CurrentFuture (0x60) | id=0x19 -> header = 0x79
        // id=1 -> 0x01
        // options = SUBSCRIBERS(0x02) | R(0x10) | N(0x20) = 0x32
        // scope varint = 0 -> 0x00
        // lenBytes("k") -> 0x01, 'k'
        Interest i = Interest.discoverSubscribers(1L, "k");
        assertArrayEquals(new byte[] { 0x79, 0x01, 0x32, 0x00, 0x01, 'k' }, i.encode());
    }

    @Test void discoverAllSetsAllKindBits() {
        Interest i = Interest.discoverAll(1L, "k");
        byte[] out = i.encode();
        // options byte at index 2: KEYEXPRS|SUBSCRIBERS|QUERYABLES|TOKENS
        // | R | N = 0x01|0x02|0x04|0x08|0x10|0x20 = 0x3F
        assertEquals((byte) 0x3F, out[2]);
    }

    @Test void modeFinalIgnoresOptionsOnTheWire() {
        // Passing OPT_SUBSCRIBERS and a key suffix on a Final-mode Interest:
        // canonical form zeroes the options byte at construct time, and encode
        // emits header+id only (no options byte, no key). The keySuffix field
        // stays whatever the caller passed on the in-memory record -- we only
        // guarantee wire-format correctness.
        Interest i = new Interest(1L, Interest.Mode.FINAL,
                Interest.OPT_SUBSCRIBERS, 0, "kept-in-memory",
                java.util.List.of());
        assertEquals(0, i.options());
        byte[] out = i.encode();
        assertEquals(2, out.length, "FINAL wire form is header + id only");
    }

    @Test void extensionsSetZFlag() {
        Interest i = new Interest(1L, Interest.Mode.CURRENT,
                Interest.OPT_SUBSCRIBERS, 0, "k",
                java.util.List.of(Extension.unit(1, false)));
        byte[] out = i.encode();
        // 0x19 | CURRENT(0b01)<<5=0x20 | Z=0x80 = 0xB9
        assertEquals((byte) 0xB9, out[0]);
    }

    @Test void modeFinalHeaderShapesCorrectlyWithZ() {
        Interest i = new Interest(1L, Interest.Mode.FINAL, 0, 0, null,
                java.util.List.of(Extension.unit(1, false)));
        byte[] out = i.encode();
        // 0x19 | FINAL(0b00)<<5=0x00 | Z=0x80 = 0x99
        assertEquals((byte) 0x99, out[0]);
    }

    @Test void multiByteInterestIdVarints() {
        // id=300 -> varint [0xAC, 0x02]
        Interest i = new Interest(300L, Interest.Mode.CURRENT,
                Interest.OPT_SUBSCRIBERS, 0, null,
                java.util.List.of());
        byte[] out = i.encode();
        assertEquals((byte) 0x39, out[0]);   // 0x19 | 0b01<<5
        assertEquals((byte) 0xAC, out[1]);
        assertEquals((byte) 0x02, out[2]);
    }

    @Test void roundtripDiscoverSubscribers() {
        Interest orig = Interest.discoverSubscribers(0xDEADBEEFL, "some/**/thing");
        Interest back = Interest.decode(orig.encode());
        assertEquals(0xDEADBEEFL, back.id());
        assertEquals(Interest.Mode.CURRENT_FUTURE, back.mode());
        assertTrue(back.wantsSubscribers());
        assertFalse(back.wantsKeyExprs());
        assertTrue(back.isRestricted());
        assertTrue(back.isNamed());
        assertEquals(0, back.keyScope());
        assertEquals("some/**/thing", back.keySuffix());
    }

    @Test void roundtripFinal() {
        Interest orig = Interest.finalOf(99L);
        Interest back = Interest.decode(orig.encode());
        assertEquals(99L, back.id());
        assertEquals(Interest.Mode.FINAL, back.mode());
        assertEquals(0, back.options());
        assertNull(back.keySuffix());
    }

    @Test void roundtripUnrestricted() {
        Interest orig = new Interest(5L, Interest.Mode.FUTURE,
                Interest.OPT_ALL_KINDS, 0, null,
                java.util.List.of());
        Interest back = Interest.decode(orig.encode());
        assertEquals(5L, back.id());
        assertEquals(Interest.Mode.FUTURE, back.mode());
        assertFalse(back.isRestricted());
        assertTrue(back.wantsKeyExprs());
        assertTrue(back.wantsSubscribers());
        assertTrue(back.wantsQueryables());
        assertTrue(back.wantsTokens());
    }

    @Test void roundtripWithExtensions() {
        Interest orig = new Interest(1L, Interest.Mode.CURRENT_FUTURE,
                Interest.OPT_SUBSCRIBERS, 0, "k",
                java.util.List.of(Extension.z64(1, false, 0xAAL),
                                  Extension.unit(2, true)));
        Interest back = Interest.decode(orig.encode());
        assertEquals(2, back.extensions().size());
        assertTrue(back.wantsSubscribers());
        assertEquals("k", back.keySuffix());
    }

    @Test void rejectsIdOverflow() {
        assertThrows(IllegalArgumentException.class,
                () -> new Interest(-1L, Interest.Mode.CURRENT, 0, 0, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Interest(1L << 33, Interest.Mode.CURRENT, 0, 0, null, null));
    }

    @Test void rejectsScopeOverflow() {
        assertThrows(IllegalArgumentException.class,
                () -> new Interest(1L, Interest.Mode.CURRENT, 0, 0x10000, null, null));
    }

    @Test void decodeRejectsWrongId() {
        // 0x1A = D_FINAL, not INTEREST
        assertThrows(IllegalArgumentException.class,
                () -> Interest.decode(new byte[] { 0x1A, 0x00 }));
    }

    @Test void allFourModesRoundtripHeaderBits() {
        for (Interest.Mode m : Interest.Mode.values()) {
            Interest orig = m == Interest.Mode.FINAL
                    ? Interest.finalOf(7L)
                    : new Interest(7L, m, Interest.OPT_SUBSCRIBERS, 0, null,
                            java.util.List.of());
            Interest back = Interest.decode(orig.encode());
            assertEquals(m, back.mode(), "mode " + m + " didn't roundtrip");
        }
    }

    @Test void constructorNormalisesRestrictedFromKeySuffix() {
        // Caller passed no R flag but did supply a keySuffix; canonical form
        // should set R and N automatically.
        Interest i = new Interest(1L, Interest.Mode.CURRENT,
                Interest.OPT_SUBSCRIBERS, 0, "abc", java.util.List.of());
        assertTrue(i.isRestricted());
        assertTrue(i.isNamed());
    }
}
