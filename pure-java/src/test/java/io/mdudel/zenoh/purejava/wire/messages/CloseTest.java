/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.wire.messages;

import io.mdudel.zenoh.purejava.wire.Extension;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CloseTest {

    // ---- reason constants + name lookup --------------------------------

    @Test void reasonConstantsMatchSpec() {
        // Values from commons/zenoh-protocol/src/transport/close.rs
        assertEquals(0x00, Close.REASON_GENERIC);
        assertEquals(0x01, Close.REASON_UNSUPPORTED);
        assertEquals(0x02, Close.REASON_INVALID);
        assertEquals(0x03, Close.REASON_MAX_SESSIONS);
        assertEquals(0x04, Close.REASON_MAX_LINKS);
        assertEquals(0x05, Close.REASON_EXPIRED);
        assertEquals(0x06, Close.REASON_UNRESPONSIVE);
        assertEquals(0x07, Close.REASON_CONNECTION_TO_SELF);
    }

    @Test void reasonNameKnownValues() {
        assertEquals("GENERIC",             Close.reasonName(0x00));
        assertEquals("UNSUPPORTED",         Close.reasonName(0x01));
        assertEquals("INVALID",             Close.reasonName(0x02));
        assertEquals("MAX_SESSIONS",        Close.reasonName(0x03));
        assertEquals("MAX_LINKS",           Close.reasonName(0x04));
        assertEquals("EXPIRED",             Close.reasonName(0x05));
        assertEquals("UNRESPONSIVE",        Close.reasonName(0x06));
        assertEquals("CONNECTION_TO_SELF",  Close.reasonName(0x07));
    }

    @Test void reasonNameUnknownFallsBackToHex() {
        assertEquals("UNKNOWN(0xff)", Close.reasonName(0xFF));
    }

    // ---- encode --------------------------------------------------------

    @Test void encodeLinkGeneric() {
        Close c = Close.linkWithReason(Close.REASON_GENERIC);
        byte[] out = c.encode();
        // header: id=0x03, S=0 (link), Z=0 -> 0x03
        assertEquals(0x03, out[0] & 0xFF);
        assertEquals(0x00, out[1] & 0xFF);   // reason
        assertEquals(2, out.length);
    }

    @Test void encodeSessionExpired() {
        Close c = new Close(Close.REASON_EXPIRED, true, List.of());
        byte[] out = c.encode();
        // header: id=0x03 | S=0x20 = 0x23
        assertEquals(0x23, out[0] & 0xFF);
        assertEquals(0x05, out[1] & 0xFF);   // EXPIRED
        assertEquals(2, out.length);
    }

    @Test void encodeWithExtensionsSetsZFlag() {
        Close c = new Close(Close.REASON_GENERIC, false,
                List.of(Extension.z64(1, false, 99L)));
        byte[] out = c.encode();
        // header: Z=1 -> 0x83
        assertEquals(0x83, out[0] & 0xFF);
        assertEquals(0x00, out[1] & 0xFF);   // reason
        // ext header: id=1 Z64 M=0 Z=0 -> 0x21
        assertEquals(0x21, out[2] & 0xFF);
        assertEquals(0x63, out[3] & 0xFF);   // varint(99)
        assertEquals(4, out.length);
    }

    // ---- decode --------------------------------------------------------

    @Test void decodeLinkClose() {
        Close c = Close.decode(new byte[]{0x03, (byte) Close.REASON_MAX_LINKS});
        assertEquals(Close.REASON_MAX_LINKS, c.reason());
        assertFalse(c.session());
        assertTrue(c.extensions().isEmpty());
    }

    @Test void decodeSessionClose() {
        Close c = Close.decode(new byte[]{0x23, (byte) Close.REASON_UNSUPPORTED});
        assertEquals(Close.REASON_UNSUPPORTED, c.reason());
        assertTrue(c.session());
    }

    @Test void decodeWithExtensionsGatedOnZ() {
        // hdr: id=0x03 | Z=0x80 = 0x83
        // reason: 0x00
        // ext: id=2, UNIT, M=0, Z=0 -> 0x02
        Close c = Close.decode(new byte[]{(byte) 0x83, 0x00, 0x02});
        assertEquals(1, c.extensions().size());
        assertEquals(2, c.extensions().get(0).id());
    }

    @Test void decodeRejectsWrongId() {
        // id=0x02 (OPEN) -> refuse
        assertThrows(IllegalArgumentException.class,
                () -> Close.decode(new byte[]{0x02, 0x00}));
    }

    @Test void encodeThenDecodeRoundtrip() {
        Close in  = new Close(Close.REASON_EXPIRED, true,
                List.of(Extension.zbuf(0, false, new byte[]{9, 8, 7})));
        Close out = Close.decode(in.encode());
        assertEquals(in, out);
    }

    // ---- construction validation ---------------------------------------

    @Test void constructorRejectsBadReason() {
        assertThrows(IllegalArgumentException.class, () -> new Close(-1,   false, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Close(256,  false, List.of()));
    }

    @Test void factorySessionGeneric() {
        Close c = Close.sessionGeneric();
        assertEquals(Close.REASON_GENERIC, c.reason());
        assertTrue(c.session());
    }
}
