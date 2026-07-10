/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.wire;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the org-prefix key resolver contract. The expected values here are
 * intentionally identical to
 * {@code io.mdudel.zenoh.ZenohClientResolveKeyTest} in the sibling
 * JNI-backed module so any behaviour drift between the two publisher
 * implementations shows up as a test failure on both sides.
 */
class KeyExprTest {

    @Test void nullOrgReturnsKeyVerbatim() {
        assertEquals("events", KeyExpr.resolveKey(null, "events"));
    }

    @Test void emptyOrgReturnsKeyVerbatim() {
        assertEquals("events", KeyExpr.resolveKey("", "events"));
    }

    @Test void simpleJoin() {
        assertEquals("acme/events", KeyExpr.resolveKey("acme", "events"));
    }

    @Test void trailingSlashOnOrgTrimmed() {
        assertEquals("acme/events", KeyExpr.resolveKey("acme/", "events"));
    }

    @Test void leadingSlashOnKeyTrimmed() {
        assertEquals("acme/events", KeyExpr.resolveKey("acme", "/events"));
    }

    @Test void bothSlashesTrimmed() {
        assertEquals("acme/events", KeyExpr.resolveKey("acme/", "/events"));
    }

    @Test void multiSegmentKey() {
        assertEquals("acme/sensors/room1", KeyExpr.resolveKey("acme", "sensors/room1"));
    }

    @Test void emptyKeyReturnsOrg() {
        assertEquals("acme", KeyExpr.resolveKey("acme", ""));
    }

    // ---- construction + basic predicates ------------------------------

    @Test void ctorRejectsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> new KeyExpr(""));
        assertThrows(NullPointerException.class, () -> new KeyExpr(null));
    }

    @Test void isConcreteDetectsWildcards() {
        assertTrue(KeyExpr.of("a").isConcrete());
        assertTrue(KeyExpr.of("a/b/c").isConcrete());
        assertFalse(KeyExpr.of("a/*/c").isConcrete());
        assertFalse(KeyExpr.of("a/**").isConcrete());
        assertFalse(KeyExpr.of("a/sensor$*&#47;temp".replace("&#47;", "/")).isConcrete());
    }

    // ---- matches(concreteKey) -----------------------------------------

    @Test void matchesExactString() {
        assertTrue(KeyExpr.of("a/b/c").matches("a/b/c"));
        assertFalse(KeyExpr.of("a/b/c").matches("a/b/d"));
    }

    @Test void singleStarMatchesOneChunk() {
        KeyExpr ke = KeyExpr.of("a/*/c");
        assertAll(
            () -> assertTrue(ke.matches("a/x/c")),
            () -> assertTrue(ke.matches("a/hello/c")),
            () -> assertFalse(ke.matches("a/c")),         // * needs at least one chunk (non-empty)
            () -> assertFalse(ke.matches("a/x/y/c")),     // * is single chunk only
            () -> assertFalse(ke.matches("a/x/d"))
        );
    }

    @Test void doubleStarMatchesZeroOrMoreChunks() {
        KeyExpr ke = KeyExpr.of("a/**/c");
        assertAll(
            () -> assertTrue(ke.matches("a/c")),          // zero chunks between
            () -> assertTrue(ke.matches("a/x/c")),        // one chunk
            () -> assertTrue(ke.matches("a/x/y/c")),      // two chunks
            () -> assertTrue(ke.matches("a/x/y/z/c")),    // three chunks
            () -> assertFalse(ke.matches("a")),           // suffix required
            () -> assertFalse(ke.matches("a/x/d"))
        );
    }

    @Test void trailingDoubleStarMatchesAnyTail() {
        KeyExpr ke = KeyExpr.of("demo/**");
        assertAll(
            () -> assertTrue(ke.matches("demo")),
            () -> assertTrue(ke.matches("demo/hello")),
            () -> assertTrue(ke.matches("demo/hello/world")),
            () -> assertFalse(ke.matches("other/hello"))
        );
    }

    @Test void bareDoubleStarMatchesEverything() {
        KeyExpr ke = KeyExpr.of("**");
        assertAll(
            () -> assertTrue(ke.matches("a")),
            () -> assertTrue(ke.matches("a/b/c/d/e/f")),
            () -> assertTrue(ke.matches("demo/greeting"))
        );
    }

    @Test void dollarStarMatchesSubChunk() {
        // sensor$* means "any sub-chunk that starts with 'sensor'"
        KeyExpr ke = KeyExpr.of("room/sensor$*/temp");
        assertAll(
            () -> assertTrue(ke.matches("room/sensor1/temp")),
            () -> assertTrue(ke.matches("room/sensorABC/temp")),
            () -> assertTrue(ke.matches("room/sensor/temp")),           // $* matches zero chars
            () -> assertFalse(ke.matches("room/thermometer1/temp")),    // wrong prefix
            () -> assertFalse(ke.matches("room/sensor/x/temp"))         // $* doesn't cross /
        );
    }

    @Test void dollarStarWithSuffixMatchesEmbedded() {
        KeyExpr ke = KeyExpr.of("log/$*Error");
        assertAll(
            () -> assertTrue(ke.matches("log/Error")),
            () -> assertTrue(ke.matches("log/FatalError")),
            () -> assertTrue(ke.matches("log/SoftError")),
            () -> assertFalse(ke.matches("log/Warning")),
            () -> assertFalse(ke.matches("log/x/Error"))                // crosses chunk
        );
    }

    // ---- intersects(other) -------------------------------------------

    @Test void intersectsIsReflexiveAndCommutative() {
        KeyExpr a = KeyExpr.of("a/*/c");
        KeyExpr b = KeyExpr.of("a/b/c");
        assertTrue(a.intersects(a));
        assertTrue(a.intersects(b));
        assertTrue(b.intersects(a));
    }

    @Test void intersectsWithDoubleStarAgainstConcrete() {
        KeyExpr wildcard = KeyExpr.of("a/**");
        assertTrue(wildcard.intersects(KeyExpr.of("a")));
        assertTrue(wildcard.intersects(KeyExpr.of("a/b")));
        assertTrue(wildcard.intersects(KeyExpr.of("a/b/c/d/e")));
        assertFalse(wildcard.intersects(KeyExpr.of("x/a")));
    }

    @Test void intersectsWildcardVsWildcard() {
        // a/*/c intersects a/b/* because they both accept a/b/c
        assertTrue(KeyExpr.of("a/*/c").intersects(KeyExpr.of("a/b/*")));
        // a/*/c does NOT intersect a/b/d/e (three chunks, wrong tail)
        assertFalse(KeyExpr.of("a/*/c").intersects(KeyExpr.of("a/b/d/e")));
        // ** intersects everything, including other wildcards
        assertTrue(KeyExpr.of("**").intersects(KeyExpr.of("foo/*/bar")));
        assertTrue(KeyExpr.of("foo/**").intersects(KeyExpr.of("foo/*/bar/baz")));
    }

    @Test void nonIntersectingKeys() {
        assertFalse(KeyExpr.of("a/b").intersects(KeyExpr.of("a/c")));
        assertFalse(KeyExpr.of("foo/*").intersects(KeyExpr.of("bar/*")));
        assertFalse(KeyExpr.of("a/b/c").intersects(KeyExpr.of("a/b")));      // length differ
    }

    @Test void intersectsWithSubChunkDsl() {
        // sensor$* covers sensor1, sensorABC, etc.
        assertTrue(KeyExpr.of("room/sensor$*/temp").intersects(KeyExpr.of("room/sensor42/temp")));
        // Different fixed prefixes never intersect
        assertFalse(KeyExpr.of("room/sensor$*/temp").intersects(KeyExpr.of("room/actuator1/temp")));
    }

    @Test void concreteMatchesEquivalenceWithIntersects() {
        // matches(k) MUST equal intersects(KeyExpr.of(k)) for every concrete k.
        String[] keys = { "a", "a/b", "a/b/c", "foo/bar/baz", "demo/hello/world/x/y" };
        String[] wildcards = { "a", "a/*", "a/**", "**", "foo/**", "*/bar/*", "demo/**/y", "**/y" };
        for (String w : wildcards) {
            KeyExpr ke = KeyExpr.of(w);
            for (String k : keys) {
                assertEquals(ke.intersects(KeyExpr.of(k)), ke.matches(k),
                        "wildcard='" + w + "' key='" + k + "'");
            }
        }
    }

    @Test void selfIntersects() {
        // Every KE intersects itself (short-circuit path in intersects()).
        String[] all = { "a", "a/b/c", "a/**", "a/*/c", "a/sensor$*/c", "**" };
        for (String s : all) {
            KeyExpr ke = KeyExpr.of(s);
            assertTrue(ke.intersects(ke), "self-intersect failed for '" + s + "'");
        }
    }
}
