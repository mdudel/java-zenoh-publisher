/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.wire.messages;

import io.mdudel.zenoh.purejava.wire.Extension;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameTest {

    @Test void bestEffortWithEmptyPayloadEncodesTwoBytes() {
        Frame f = new Frame(0L, false, null, null);
        byte[] out = f.encode();
        // header = 0x05 (no R, no Z), then sn varint 0.
        assertArrayEquals(new byte[] { 0x05, 0x00 }, out);
    }

    @Test void reliableFlagSetsBit5() {
        Frame f = new Frame(1L, true, null, new byte[] { (byte) 0xAB });
        byte[] out = f.encode();
        assertEquals((byte) 0x25, out[0]);       // 0x05 | R(0x20)
        assertEquals((byte) 0x01, out[1]);       // sn=1
        assertEquals((byte) 0xAB, out[2]);       // payload passthrough
    }

    @Test void extensionsSetZFlagBit7() {
        Frame f = new Frame(0L, false,
                List.of(Extension.unit(1, false)),
                new byte[] { 0x00 });
        byte[] out = f.encode();
        assertEquals((byte) 0x85, out[0]);       // 0x05 | Z(0x80)
    }

    @Test void multiByteSnVarints() {
        // sn = 300 → varint [0xAC, 0x02]
        Frame f = new Frame(300L, true, null, new byte[0]);
        byte[] out = f.encode();
        assertEquals((byte) 0x25, out[0]);
        assertEquals((byte) 0xAC, out[1]);
        assertEquals((byte) 0x02, out[2]);
    }

    @Test void ofPushHelperEmbedsEncodedPush() {
        Push push = Push.ofPut("k", Put.ofBytes(new byte[] { 'v' }));
        byte[] pushBytes = push.encode();
        Frame f = Frame.ofPush(42L, true, push);
        byte[] out = f.encode();
        // header, sn, then verbatim push bytes
        assertEquals((byte) 0x25, out[0]);
        assertEquals((byte) 0x2A, out[1]);    // sn=42
        // tail matches pushBytes
        byte[] tail = new byte[pushBytes.length];
        System.arraycopy(out, 2, tail, 0, tail.length);
        assertArrayEquals(pushBytes, tail);
    }

    @Test void roundtripBestEffortNoExts() {
        Frame orig = new Frame(7L, false, null,
                new byte[] { 0x01, 0x02, 0x03, 0x04 });
        Frame back = Frame.decode(orig.encode());
        assertEquals(7L, back.sn());
        assertFalse(back.reliable());
        assertTrue(back.extensions().isEmpty());
        assertArrayEquals(new byte[] { 0x01, 0x02, 0x03, 0x04 }, back.payload());
    }

    @Test void roundtripReliableWithExtensions() {
        Frame orig = new Frame(0x1234L, true,
                List.of(Extension.z64(1, false, 0xAAL),
                        Extension.zbuf(2, false, new byte[] { 'x' })),
                new byte[] { 'p', 'a', 'y' });
        Frame back = Frame.decode(orig.encode());
        assertEquals(0x1234L, back.sn());
        assertTrue(back.reliable());
        assertEquals(2, back.extensions().size());
        assertArrayEquals(new byte[] { 'p', 'a', 'y' }, back.payload());
    }

    @Test void roundtripWithEmbeddedPushPut() {
        Put put = Put.ofString("payload text");
        Push push = Push.ofPut("some/key", put);
        Frame orig = Frame.ofPush(99L, true, push);
        Frame back = Frame.decode(orig.encode());
        assertEquals(99L, back.sn());
        // Payload of a Frame in this MVP is opaque bytes containing the network message(s).
        Push pushBack = Push.decode(back.payload());
        assertEquals("some/key", pushBack.keySuffix());
        Put putBack = Put.decode(pushBack.body());
        assertEquals("payload text",
                new String(putBack.payload(), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test void rejectsWrongId() {
        byte[] bad = new byte[] { 0x04, 0x00 };  // KEEPALIVE, not FRAME
        assertThrows(IllegalArgumentException.class, () -> Frame.decode(bad));
    }

    @Test void rejectsNegativeSn() {
        assertThrows(IllegalArgumentException.class,
                () -> new Frame(-1L, true, null, null));
    }
}
