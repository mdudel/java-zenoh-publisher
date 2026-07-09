/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.wire;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
