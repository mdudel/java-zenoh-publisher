/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.wire.messages;

import io.mdudel.zenoh.purejava.wire.Encoding;
import io.mdudel.zenoh.purejava.wire.Timestamp;
import io.mdudel.zenoh.purejava.wire.ZenohId;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PutTest {

    @Test void mvpBareBytesPayloadHeaderIsIdOnly() {
        // No T, no E (EMPTY), no Z. header = 0x01.
        // payload "hi" (2 bytes): lenBytes -> [0x02, 'h', 'i']
        Put p = Put.ofBytes(new byte[] { 'h', 'i' });
        byte[] out = p.encode();
        assertArrayEquals(new byte[] { 0x01, 0x02, 'h', 'i' }, out);
    }

    @Test void emptyPayloadStillEncodesLengthZero() {
        Put p = Put.ofBytes(new byte[0]);
        byte[] out = p.encode();
        assertArrayEquals(new byte[] { 0x01, 0x00 }, out);
    }

    @Test void encodingFlagSetWhenNonEmpty() {
        // Encoding id=5 → composite 0x0A. header: 0x01 | E=0x40 = 0x41.
        Put p = new Put(null, Encoding.of(5), null, new byte[] { 'X' });
        byte[] out = p.encode();
        assertArrayEquals(new byte[] { 0x41, 0x0A, 0x01, 'X' }, out);
    }

    @Test void timestampFlagSetAndTimestampFirst() {
        // T=0x20, no E, no Z. Timestamp = ntp64=1, id=[0xAA] → [0x01, 0x01, 0xAA]
        // Payload = "" → [0x00]
        Put p = new Put(new Timestamp(1L, new ZenohId(new byte[] { (byte) 0xAA })),
                Encoding.EMPTY, null, new byte[0]);
        byte[] out = p.encode();
        assertArrayEquals(
                new byte[] { 0x21, 0x01, 0x01, (byte) 0xAA, 0x00 },
                out);
    }

    @Test void allThreeFlagsSetAtOnce() {
        // T=0x20, E=0x40, Z=0x80. Header = 0x01 | 0x20 | 0x40 | 0x80 = 0xE1
        // But Z=0 for ofString convenience; build explicitly with an extension.
        Put p = new Put(
                new Timestamp(0L, new ZenohId(new byte[] { 0x01 })),
                Encoding.of(2),
                java.util.List.of(io.mdudel.zenoh.purejava.wire.Extension.unit(7, false)),
                new byte[] { 'x' });
        byte[] out = p.encode();
        assertEquals((byte) 0xE1, out[0]);
    }

    @Test void roundtripStringPayload() {
        Put orig = Put.ofString("hello, world");
        Put back = Put.decode(orig.encode());
        assertArrayEquals("hello, world".getBytes(StandardCharsets.UTF_8), back.payload());
        assertEquals(Encoding.ID_ZENOH_STRING, back.encoding().id());
        assertNull(back.timestamp());
        assertTrue(back.extensions().isEmpty());
    }

    @Test void roundtripJsonPayload() {
        Put orig = Put.ofJson("{\"k\":42}");
        Put back = Put.decode(orig.encode());
        assertEquals(Encoding.ID_APPLICATION_JSON, back.encoding().id());
        assertEquals("{\"k\":42}", new String(back.payload(), StandardCharsets.UTF_8));
    }

    @Test void roundtripWithTimestampAndEncoding() {
        Timestamp ts = new Timestamp(0x1234_5678L,
                new ZenohId(new byte[] { 1, 2, 3, 4 }));
        Encoding enc = Encoding.of(Encoding.ID_TEXT_PLAIN, "charset=utf-8");
        byte[] payload = "SOME BODY".getBytes(StandardCharsets.UTF_8);
        Put orig = new Put(ts, enc, null, payload);
        Put back = Put.decode(orig.encode());
        assertEquals(ts.ntp64(), back.timestamp().ntp64());
        assertEquals(ts.id(), back.timestamp().id());
        assertEquals(enc, back.encoding());
        assertArrayEquals(payload, back.payload());
    }

    @Test void unknownExtensionSkippedTransparently() {
        // Craft a Put with an unknown ext id — decode should still succeed and expose it verbatim.
        Put orig = new Put(null, Encoding.EMPTY,
                java.util.List.of(io.mdudel.zenoh.purejava.wire.Extension.unit(11, false)),
                new byte[] { 'z' });
        Put back = Put.decode(orig.encode());
        assertEquals(1, back.extensions().size());
        assertEquals(11, back.extensions().get(0).id());
        assertArrayEquals(new byte[] { 'z' }, back.payload());
    }

    @Test void wrongIdRejected() {
        // Craft bytes whose id nibble isn't 0x01
        byte[] bad = new byte[] { 0x02, 0x00 };  // looks like DEL
        assertThrows(IllegalArgumentException.class, () -> Put.decode(bad));
    }

    @Test void largePayloadRoundtrips() {
        // 8 KB payload (fits in default WBuf growth, tests varint length across boundaries)
        byte[] big = new byte[8192];
        for (int i = 0; i < big.length; i++) big[i] = (byte) (i & 0xFF);
        Put orig = Put.ofBytes(big);
        Put back = Put.decode(orig.encode());
        assertArrayEquals(big, back.payload());
    }
}
