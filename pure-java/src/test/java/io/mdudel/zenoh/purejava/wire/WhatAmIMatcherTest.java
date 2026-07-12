/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.wire;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WhatAmIMatcherTest {

    @Test void bitsForEachRole() {
        assertEquals(0b001, WhatAmIMatcher.of(WhatAmI.ROUTER).bits());
        assertEquals(0b010, WhatAmIMatcher.of(WhatAmI.PEER).bits());
        assertEquals(0b100, WhatAmIMatcher.of(WhatAmI.CLIENT).bits());
    }

    @Test void anyIsAllThreeBits() {
        assertEquals(0b111, WhatAmIMatcher.any().bits());
        WhatAmIMatcher any = WhatAmIMatcher.any();
        assertTrue(any.matchesRouter());
        assertTrue(any.matchesPeer());
        assertTrue(any.matchesClient());
        assertEquals(EnumSet.allOf(WhatAmI.class), any.roles());
    }

    @Test void ofSetOrsRolesTogether() {
        WhatAmIMatcher m = WhatAmIMatcher.of(EnumSet.of(WhatAmI.ROUTER, WhatAmI.PEER));
        assertEquals(0b011, m.bits());
        assertTrue(m.matches(WhatAmI.ROUTER));
        assertTrue(m.matches(WhatAmI.PEER));
        assertFalse(m.matches(WhatAmI.CLIENT));
    }

    @Test void ofSetRejectsEmpty() {
        assertThrows(IllegalArgumentException.class,
                () -> WhatAmIMatcher.of(EnumSet.noneOf(WhatAmI.class)));
        assertThrows(IllegalArgumentException.class,
                () -> WhatAmIMatcher.of((Set<WhatAmI>) null));
    }

    @Test void fromBitsAcceptsValid() {
        WhatAmIMatcher m = WhatAmIMatcher.fromBits(0b101);
        assertTrue(m.matchesRouter());
        assertFalse(m.matchesPeer());
        assertTrue(m.matchesClient());
    }

    @Test void fromBitsRejectsZero() {
        assertThrows(IllegalArgumentException.class,
                () -> WhatAmIMatcher.fromBits(0));
    }

    @Test void fromBitsMasksReservedInputBitsButRejectsReservedOnly() {
        // 0b1000 is entirely reserved; masked to 0b000 = no roles = reject
        assertThrows(IllegalArgumentException.class,
                () -> WhatAmIMatcher.fromBits(0b1000));
    }

    @Test void equalsAndHashCode() {
        assertEquals(WhatAmIMatcher.any(), WhatAmIMatcher.fromBits(0b111));
        assertEquals(WhatAmIMatcher.of(WhatAmI.PEER),
                     WhatAmIMatcher.fromBits(0b010));
        assertEquals(WhatAmIMatcher.any().hashCode(),
                     WhatAmIMatcher.fromBits(0b111).hashCode());
        assertNotEquals(WhatAmIMatcher.of(WhatAmI.ROUTER),
                        WhatAmIMatcher.of(WhatAmI.PEER));
    }
}
