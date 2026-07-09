/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of a clean-room pure-Java implementation of the Eclipse
 * Zenoh 1.x wire protocol. It is not a copy of any Zenoh source code.
 */
package io.mdudel.zenoh.purejava.wire;

import java.util.Objects;

/**
 * Zenoh key expression handling. For the publisher MVP we only need:
 *
 * <ul>
 *   <li>A canonical string form (used verbatim as the wire representation
 *       inside the {@code Put} message's key-expression field).</li>
 *   <li>The {@code org + "/" + keyExpr} prefix resolver, kept
 *       byte-identical to the resolver in the JNI-backed
 *       {@code io.mdudel.zenoh.ZenohClient} so the two publisher
 *       implementations produce the same effective key.</li>
 * </ul>
 *
 * <p>Wildcard-intersect matching (needed for subscribers) is deferred.</p>
 */
public final class KeyExpr {

    private final String value;

    public KeyExpr(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public String value() { return value; }

    @Override public String toString() { return value; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof KeyExpr other)) return false;
        return value.equals(other.value);
    }

    @Override public int hashCode() { return value.hashCode(); }

    /**
     * Prepend {@code org} to {@code keyExpr} with slash normalisation.
     *
     * <p>Behaviour must remain byte-identical to
     * {@code io.mdudel.zenoh.ZenohClient.resolveKey(String, String)}
     * in the JNI-backed sibling module - the two implementations are
     * meant to be drop-in swappable, which requires the effective wire
     * key be the same for the same inputs. If the JNI version's
     * behaviour changes, this must change with it.</p>
     *
     * <p>Examples:</p>
     * <pre>
     *   resolveKey(null,   "tracks") -> "tracks"
     *   resolveKey("",     "tracks") -> "tracks"
     *   resolveKey("acme", "tracks") -> "acme/tracks"
     *   resolveKey("acme/","tracks") -> "acme/tracks"
     *   resolveKey("acme","/tracks") -> "acme/tracks"
     *   resolveKey("acme/","/tracks")-> "acme/tracks"
     *   resolveKey("acme",  "")       -> "acme"
     * </pre>
     */
    public static String resolveKey(String org, String keyExpr) {
        String k = keyExpr == null ? "" : keyExpr;
        if (org == null || org.isEmpty()) return k;
        String o = org;
        while (o.endsWith("/")) o = o.substring(0, o.length() - 1);
        while (k.startsWith("/")) k = k.substring(1);
        if (o.isEmpty()) return k;
        if (k.isEmpty()) return o;
        return o + "/" + k;
    }
}
