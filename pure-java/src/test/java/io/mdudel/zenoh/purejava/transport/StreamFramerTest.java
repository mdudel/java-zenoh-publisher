/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.transport;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamFramerTest {

    @Test void writeEmptyFrameEmitsTwoZeroBytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StreamFramer.writeFrame(out, new byte[0]);
        assertArrayEquals(new byte[] { 0x00, 0x00 }, out.toByteArray());
    }

    @Test void writeSmallPayloadPrefixesLittleEndianLength() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StreamFramer.writeFrame(out, new byte[] { 'a', 'b', 'c' });
        assertArrayEquals(new byte[] { 0x03, 0x00, 'a', 'b', 'c' }, out.toByteArray());
    }

    @Test void writeLargePayloadEncodesHighByteCorrectly() throws IOException {
        // 300 bytes → LE length = [0x2C, 0x01]
        byte[] payload = new byte[300];
        for (int i = 0; i < 300; i++) payload[i] = (byte) (i & 0xFF);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StreamFramer.writeFrame(out, payload);
        byte[] result = out.toByteArray();
        assertEquals(302, result.length);
        assertEquals((byte) 0x2C, result[0]);
        assertEquals((byte) 0x01, result[1]);
        // Payload bytes intact
        assertArrayEquals(payload, Arrays.copyOfRange(result, 2, result.length));
    }

    @Test void writeMaxSizedPayloadEncodesFfffLength() throws IOException {
        byte[] payload = new byte[StreamFramer.MAX_FRAME_BYTES];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StreamFramer.writeFrame(out, payload);
        byte[] result = out.toByteArray();
        assertEquals(2 + StreamFramer.MAX_FRAME_BYTES, result.length);
        assertEquals((byte) 0xFF, result[0]);
        assertEquals((byte) 0xFF, result[1]);
    }

    @Test void writeRejectsOversizedPayload() {
        byte[] tooBig = new byte[StreamFramer.MAX_FRAME_BYTES + 1];
        assertThrows(IllegalArgumentException.class,
                () -> StreamFramer.writeFrame(new ByteArrayOutputStream(), tooBig));
    }

    @Test void writeRejectsNullPayload() {
        assertThrows(IllegalArgumentException.class,
                () -> StreamFramer.writeFrame(new ByteArrayOutputStream(), null));
    }

    @Test void readSmallFrame() throws IOException {
        byte[] wire = new byte[] { 0x03, 0x00, 'x', 'y', 'z' };
        byte[] payload = StreamFramer.readFrame(new ByteArrayInputStream(wire));
        assertArrayEquals(new byte[] { 'x', 'y', 'z' }, payload);
    }

    @Test void readEmptyFrame() throws IOException {
        byte[] wire = new byte[] { 0x00, 0x00 };
        byte[] payload = StreamFramer.readFrame(new ByteArrayInputStream(wire));
        assertEquals(0, payload.length);
    }

    @Test void readMultipleFramesInSuccession() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        StreamFramer.writeFrame(buf, new byte[] { 'A' });
        StreamFramer.writeFrame(buf, new byte[] { 'B', 'B' });
        StreamFramer.writeFrame(buf, new byte[] { 'C', 'C', 'C' });
        ByteArrayInputStream in = new ByteArrayInputStream(buf.toByteArray());
        assertArrayEquals(new byte[] { 'A' },           StreamFramer.readFrame(in));
        assertArrayEquals(new byte[] { 'B', 'B' },      StreamFramer.readFrame(in));
        assertArrayEquals(new byte[] { 'C', 'C', 'C' }, StreamFramer.readFrame(in));
    }

    @Test void readReassemblesAcrossShortReads() throws IOException {
        // Simulate a stream that only ever hands back 1 byte per read().
        byte[] wire = new byte[] { 0x05, 0x00, 1, 2, 3, 4, 5 };
        InputStream in = new InputStream() {
            int pos = 0;
            @Override public int read() { return pos < wire.length ? wire[pos++] & 0xFF : -1; }
            @Override public int read(byte[] b, int off, int len) {
                if (pos >= wire.length) return -1;
                b[off] = wire[pos++];
                return 1;  // pathological: always 1 byte
            }
        };
        byte[] payload = StreamFramer.readFrame(in);
        assertArrayEquals(new byte[] { 1, 2, 3, 4, 5 }, payload);
    }

    @Test void cleanEofAtFrameBoundaryThrowsWithDistinctMessage() {
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
        EOFException e = assertThrows(EOFException.class,
                () -> StreamFramer.readFrame(in));
        assertTrue(e.getMessage().contains("clean EOF"), "message was: " + e.getMessage());
    }

    @Test void midLengthEofIsFlaggedAsUnclean() {
        // Only one byte of the 2-byte length available.
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[] { 0x05 });
        EOFException e = assertThrows(EOFException.class,
                () -> StreamFramer.readFrame(in));
        assertTrue(e.getMessage().contains("1 byte of 2-byte length prefix"),
                "message was: " + e.getMessage());
    }

    @Test void truncatedPayloadEofIncludesExpectedVsRead() {
        // Header says 5, only 2 payload bytes present.
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[] { 0x05, 0x00, 1, 2 });
        EOFException e = assertThrows(EOFException.class,
                () -> StreamFramer.readFrame(in));
        assertTrue(e.getMessage().contains("2 of 5"), "message was: " + e.getMessage());
    }

    @Test void roundTripBigPayloadThroughByteArrayStreams() throws IOException {
        byte[] payload = new byte[4096];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StreamFramer.writeFrame(out, payload);
        byte[] back = StreamFramer.readFrame(new ByteArrayInputStream(out.toByteArray()));
        assertArrayEquals(payload, back);
    }
}
