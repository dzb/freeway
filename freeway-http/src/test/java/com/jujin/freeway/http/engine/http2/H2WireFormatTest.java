package com.jujin.freeway.http.engine.http2;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jujin.freeway.http.engine.http2.frame.FrameFlag;
import com.jujin.freeway.http.engine.http2.frame.FrameHeader;
import com.jujin.freeway.http.engine.http2.frame.FrameType;
import com.jujin.freeway.http.engine.http2.frame.GoawayFrame;
import com.jujin.freeway.http.engine.http2.frame.ResetStreamFrame;
import com.jujin.freeway.http.engine.http2.frame.SettingIdentifier;
import com.jujin.freeway.http.engine.http2.frame.SettingParameter;
import com.jujin.freeway.http.engine.http2.frame.SettingsFrame;
import com.jujin.freeway.http.engine.http2.frame.WindowUpdateFrame;
import com.jujin.freeway.http.engine.http2.hpack.HPackContext;
import com.jujin.freeway.http.engine.http2.util.Http2ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Byte-level verification of HTTP/2 frame encoding against RFC 7540 §4-§6
 * and RFC 7541 §5. These lock the wire format after several encoding bugs
 * (missing frame headers in RST_STREAM/WINDOW_UPDATE) were found and fixed.
 */
class H2WireFormatTest {

    private static final HexFormat HEX = HexFormat.of();

    @Test
    void frameHeaderEncodesNineBytesRfcLayout() {
        // RFC 7540 §4.1: Length(24) | Type(8) | Flags(8) | R(1)+StreamID(31)
        byte[] header = FrameHeader.encode(0x1234, FrameType.HEADERS,
            FrameFlag.FlagSet.of(FrameFlag.END_HEADERS), 0x7);
        assertEquals("001234010400000007", HEX.formatHex(header),
            "length=0x1234, type=0x01, flags=0x04, stream=7");
    }

    @Test
    void frameHeaderHighBitOfStreamIdIsReserved() {
        // R bit (bit 32) must stay 0; stream id uses the low 31 bits.
        byte[] header = FrameHeader.encode(0, FrameType.PING,
            FrameFlag.FlagSet.of(FrameFlag.ACK), 0x7FFFFFFF);
        assertEquals(0x7F, header[5] & 0xFF, "R bit must not be set");
    }

    @Test
    void rstStreamEncodesCompleteFrame() {
        // RFC 7540 §6.4: RST_STREAM = frame header + 4-byte error code.
        var frame = new ResetStreamFrame(Http2ErrorCode.REFUSED_STREAM, 5);
        byte[] encoded = frame.encode();
        assertEquals("00000403000000000500000007", HEX.formatHex(encoded),
            "len=4, type=0x03, stream=5, error=0x7 (REFUSED_STREAM)");
    }

    @Test
    void windowUpdateEncodesCompleteFrame() {
        // RFC 7540 §6.9: WINDOW_UPDATE = frame header + 4-byte increment.
        var frame = new WindowUpdateFrame(3, 65535);
        byte[] encoded = frame.encode();
        assertEquals("0000040800000000030000ffff", HEX.formatHex(encoded),
            "len=4, type=0x08, stream=3, increment=65535");
    }

    @Test
    void goAwayEncodesLastStreamAndError() {
        // RFC 7540 §6.8: GOAWAY = header + Last-Stream-ID(4) + Error(4).
        var frame = new GoawayFrame(Http2ErrorCode.NO_ERROR, 41);
        byte[] encoded = frame.encode();
        assertEquals("0000080700000000000000002900000000", HEX.formatHex(encoded),
            "len=8, type=0x07, stream=0, last-stream=41, error=0");
    }

    @Test
    void settingsFrameEncodesSixByteParameters() {
        // RFC 7540 §6.5: each SETTINGS parameter = 16-bit id + 32-bit value.
        var sf = new SettingsFrame(new FrameHeader(0, FrameType.SETTINGS, FrameFlag.NONE, 0));
        sf.params.add(new SettingParameter(SettingIdentifier.SETTINGS_MAX_FRAME_SIZE, 16384));
        sf.params.add(new SettingParameter(SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE, 65535));
        byte[] encoded = sf.encode();
        // len=12 (2 params), type=0x04, flags=0x00, stream=0
        assertEquals("00000c040000000000", HEX.formatHex(Arrays.copyOf(encoded, 9)));
        // param 1: id=0x0005 (MAX_FRAME_SIZE), value=0x00004000
        assertEquals("000500004000", HEX.formatHex(Arrays.copyOfRange(encoded, 9, 15)));
        // param 2: id=0x0004 (INITIAL_WINDOW_SIZE), value=0x0000FFFF
        assertEquals("00040000ffff", HEX.formatHex(Arrays.copyOfRange(encoded, 15, 21)));
    }

    @Test
    void settingsAckIsZeroLengthFrame() {
        byte[] ack = FrameHeader.encode(0, FrameType.SETTINGS,
            FrameFlag.FlagSet.of(FrameFlag.ACK), 0);
        assertEquals("000000040100000000", HEX.formatHex(ack),
            "SETTINGS ACK: len=0, type=0x04, flags=0x01");
    }

    @Test
    void encodedHeadersRoundTripThroughOwnDecoder() throws Exception {
        // End-to-end: the encoder's output must be decodable by the same
        // codec — this would catch any asymmetric literal/indexed encoding.
        var out = new ByteArrayOutputStream();
        var hpack = new HPackContext();
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put(":status", List.of("200"));
        headers.put("content-type", List.of("application/json; charset=utf-8"));
        headers.put("x-custom", List.of("value-1", "value-2"));
        hpack.writeResponseHeaders(headers, out, 7, false);

        // strip the 9-byte HEADERS frame header, decode the block
        byte[] block = Arrays.copyOfRange(out.toByteArray(), 9, out.size());
        var decoded = new HPackContext().decode(block);
        assertEquals("200", decoded.get(0).value, ":status via indexed 0x88");
        assertEquals("content-type", decoded.get(1).name);
        assertEquals("application/json; charset=utf-8", decoded.get(1).value);
        assertEquals("x-custom", decoded.get(2).name);
        assertEquals("value-1", decoded.get(2).value);
        assertEquals("value-2", decoded.get(3).value);
    }
}
