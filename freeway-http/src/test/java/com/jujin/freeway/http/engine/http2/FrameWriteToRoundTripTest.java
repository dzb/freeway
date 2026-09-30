package com.jujin.freeway.http.engine.http2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A frame that parses must serialize back to the same bytes.
 *
 * <p>Round-trip is the only honest check for a {@code writeTo} nobody calls:
 * the built-in engine is a server and writes its own header blocks through
 * {@code HPackContext}, so most of these frame serializers have no production
 * caller. Two of them were also wrong — {@code DataFrame} emitted its payload
 * with no frame header, and {@code HeadersFrame} dropped the pad-length byte,
 * the PRIORITY field and the padding, all three of which it parses. Wrong code
 * with no caller is worse than no code: the next caller inherits the wire
 * format.
 */
class FrameWriteToRoundTripTest {

    private static byte[] write(BaseFrame frame) throws IOException {
        var out = new ByteArrayOutputStream();
        frame.writeTo(out);
        return out.toByteArray();
    }

    private static BaseFrame read(byte[] wire) throws IOException {
        try (InputStream in = new ByteArrayInputStream(wire)) {
            return FrameSerializer.deserialize(in, wire.length);
        }
    }

    @Test
    void dataFrameWritesAHeaderAndThePayload() throws Exception {
        byte[] payload = "hello".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        var header = new FrameHeader(payload.length, FrameType.DATA,
            FrameFlag.FlagSet.of(FrameFlag.END_STREAM), 3);
        var frame = new DataFrame(header, payload);

        byte[] wire = write(frame);
        assertEquals(9 + payload.length, wire.length,
            "9-byte frame header plus payload — the previous implementation "
                + "wrote the payload alone");

        var decoded = (DataFrame) read(wire);
        assertEquals(FrameType.DATA, decoded.header().type());
        assertEquals(3, decoded.header().streamId());
        assertTrue(decoded.header().flags().contains(FrameFlag.END_STREAM));
        assertArrayEquals(payload, decoded.body);
    }

    @Test
    void headersFrameRoundTripsHeaderBlock() throws Exception {
        byte[] block = {(byte) 0x82, (byte) 0x86, (byte) 0x84};
        var frame = HeadersFrame.parse(block, new FrameHeader(block.length,
            FrameType.HEADERS, FrameFlag.FlagSet.of(FrameFlag.END_HEADERS), 5));

        var decoded = (HeadersFrame) read(write(frame));
        assertEquals(FrameType.HEADERS, decoded.header().type());
        assertEquals(5, decoded.header().streamId());
        assertArrayEquals(block, decoded.headerBlock());
    }

    @Test
    void headersFrameRoundTripsPadding() throws Exception {
        // PADDED HEADERS: [padLen][block][padding]
        byte[] block = {(byte) 0x82, (byte) 0x86};
        byte[] payload = new byte[1 + block.length + 3];
        payload[0] = 3;                       // pad length
        System.arraycopy(block, 0, payload, 1, block.length);

        var frame = HeadersFrame.parse(payload, new FrameHeader(payload.length,
            FrameType.HEADERS, FrameFlag.FlagSet.of(FrameFlag.PADDED), 7));

        byte[] wire = write(frame);
        assertEquals(9 + payload.length, wire.length,
            "padding must be written back — the previous implementation dropped "
                + "the pad-length byte and the padding entirely");

        var decoded = (HeadersFrame) read(wire);
        assertTrue(decoded.header().flags().contains(FrameFlag.PADDED));
        assertArrayEquals(block, decoded.headerBlock());
    }

    @Test
    void headersFrameRoundTripsPriorityField() throws Exception {
        // PRIORITY HEADERS: [depStreamId:4][weight:1][block]
        byte[] block = {(byte) 0x82, (byte) 0x86, (byte) 0x84};
        byte[] payload = new byte[5 + block.length];
        BinUtils.writeInt(payload, 0, 9, 4);      // dependent stream, != our own
        payload[4] = (byte) 200;                      // weight
        System.arraycopy(block, 0, payload, 5, block.length);

        var frame = HeadersFrame.parse(payload, new FrameHeader(payload.length,
            FrameType.HEADERS, FrameFlag.FlagSet.of(FrameFlag.PRIORITY), 3));

        byte[] wire = write(frame);
        assertEquals(9 + payload.length, wire.length,
            "the PRIORITY field must be written back — the previous "
                + "implementation parsed and then dropped it");

        var decoded = (HeadersFrame) read(wire);
        assertTrue(decoded.header().flags().contains(FrameFlag.PRIORITY));
        assertArrayEquals(block, decoded.headerBlock());
    }

    @Test
    void paddedAndPrioritisedHeadersFrameRoundTrips() throws Exception {
        // [padLen][depId:4][weight][block][padding]
        byte[] block = {(byte) 0x82};
        int pad = 2;
        byte[] payload = new byte[1 + 4 + 1 + block.length + pad];
        payload[0] = (byte) pad;
        BinUtils.writeInt(payload, 1, 11, 4);
        payload[5] = (byte) 128;
        System.arraycopy(block, 0, payload, 6, block.length);

        var frame = HeadersFrame.parse(payload, new FrameHeader(payload.length,
            FrameType.HEADERS,
            FrameFlag.FlagSet.of(FrameFlag.PADDED, FrameFlag.PRIORITY), 1));

        var decoded = (HeadersFrame) read(write(frame));
        assertTrue(decoded.header().flags().contains(FrameFlag.PADDED));
        assertTrue(decoded.header().flags().contains(FrameFlag.PRIORITY));
        assertArrayEquals(block, decoded.headerBlock());
        assertEquals(payload.length, decoded.header().length());
    }

    @Test
    void emptyDataFrameStillWritesAHeader() throws Exception {
        var frame = new DataFrame(new FrameHeader(0, FrameType.DATA,
            FrameFlag.NONE, 0), new byte[0]);
        assertEquals(9, write(frame).length,
            "an empty DATA frame is still 9 bytes on the wire");
    }

    @Test
    void settingsFrameRoundTrips() throws Exception {
        var frame = new SettingsFrame(
            new FrameHeader(0, FrameType.SETTINGS, FrameFlag.NONE, 0));
        frame.params.add(new SettingParameter(
            SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, 100));
        frame.params.add(new SettingParameter(
            SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE, 65535));

        byte[] wire = write(frame);
        assertEquals(9 + 12, wire.length, "9-byte header plus two 6-byte parameters");

        var decoded = (SettingsFrame) read(wire);
        assertEquals(2, decoded.params.size());
        assertEquals(100, decoded.params.get(0).value);
        assertEquals(65535, decoded.params.get(1).value);
    }
}
