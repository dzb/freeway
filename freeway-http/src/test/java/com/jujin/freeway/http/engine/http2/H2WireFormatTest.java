package com.jujin.freeway.http.engine.http2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.Socket;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.http.engine.http2.hpack.HPackContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
    void settingsHeaderTableSizeValidatesUnsignedRange() {
        // RFC 7540 §6.5.2: SETTINGS_HEADER_TABLE_SIZE is a 32-bit unsigned
        // value. The wire parse is unsigned, so only programmatically built
        // settings can carry a negative or > uint32 value — validation must
        // reject those before they poison the HPACK decoder state.
        var id = SettingIdentifier.SETTINGS_HEADER_TABLE_SIZE;
        assertTrue(!id.validateValue(-1),
            "a negative table size must be rejected");
        assertTrue(!id.validateValue(0x1_0000_0000L),
            "a value beyond uint32 must be rejected");
        assertTrue(id.validateValue(0),
            "the RFC minimum (0 = no dynamic table) must be accepted");
        assertTrue(id.validateValue(4096),
            "the RFC default must be accepted");
        assertTrue(id.validateValue(0xFFFFFFFFL),
            "the full uint32 range must be accepted");
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
    void continuationRejectsNonEndHeadersFlags() {
        byte[] header = FrameHeader.encode(0, FrameType.CONTINUATION,
            FrameFlag.FlagSet.of(FrameFlag.END_STREAM), 1);
        assertThrows(Http2Exception.class, () -> FrameSerializer.deserialize(
            new ByteArrayInputStream(header)));
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

    @Test
    void inboundResetBurstFailsWithEnhanceYourCalm() throws Exception {
        // CVE-2023-44487 family: MAX_CONCURRENT_STREAMS alone does not stop
        // open-reset-reopen cycling. Past the burst window the reader must
        // surface ENHANCE_YOUR_CALM so handle() GOAWAYs the connection.
        var executor = Executors.newSingleThreadExecutor();
        try (var socket = new Socket()) {
            var conn = new Http2Connection(
                socket,
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream(),
                executor,
                (stream, in, o, headers) -> {},
                0
            );
            var m = Http2Connection.class.getDeclaredMethod("noteInboundReset");
            m.setAccessible(true);
            for (int i = 0; i < 200; i++) {
                m.invoke(conn);
            }
            try {
                m.invoke(conn);
                fail("expected rapid-reset burst to fail");
            } catch (InvocationTargetException e) {
                assertTrue(e.getCause() instanceof Http2Exception,
                    "burst must fail as Http2Exception, got: " + e.getCause());
                assertEquals(Http2ErrorCode.ENHANCE_YOUR_CALM,
                    ((Http2Exception) e.getCause()).errorCode(),
                    "burst must ask the peer to calm down, not PROTOCOL_ERROR");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void customBurstLimitAppliesFromConstructor() throws Exception {
        // The guard is tuned per connection (config keys
        // freeway.http.h2.reset-burst-limit/window); the overload must be
        // honored exactly, and 0 must disable the guard entirely.
        var executor = Executors.newSingleThreadExecutor();
        try (var socket = new Socket()) {
            var strict = new Http2Connection(
                socket,
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream(),
                executor,
                (stream, in, o, headers) -> {},
                0, 3, Duration.ofSeconds(10)
            );
            var m = Http2Connection.class.getDeclaredMethod("noteInboundReset");
            m.setAccessible(true);
            for (int i = 0; i < 3; i++) {
                m.invoke(strict);
            }
            try {
                m.invoke(strict);
                fail("expected the 4th reset to trip a limit of 3");
            } catch (InvocationTargetException e) {
                assertEquals(Http2ErrorCode.ENHANCE_YOUR_CALM,
                    ((Http2Exception) e.getCause()).errorCode());
            }

            var disabled = new Http2Connection(
                socket,
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream(),
                executor,
                (stream, in, o, headers) -> {},
                0, 0, Duration.ofSeconds(10)
            );
            for (int i = 0; i < 10; i++) {
                m.invoke(disabled);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void peerResetCountsOnlyBeforeResponseCommit() throws Exception {
        // Only the asymmetric shape trips the breaker: a cancel arriving
        // before the server committed a response. Post-response cancels are
        // ordinary client behavior and must never count.
        var executor = Executors.newSingleThreadExecutor();
        try (var socket = new Socket()) {
            var conn = new Http2Connection(
                socket,
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream(),
                executor,
                (stream, in, o, headers) -> {},
                0
            );
            var pending = new Http2Stream(1, conn,
                Map.<String, List<String>>of(), (s, in, o, h) -> {});
            conn.streams.put(1, pending);
            for (int i = 0; i < 200; i++) {
                conn.notePeerReset(pending);
            }
            try {
                conn.notePeerReset(pending);
                fail("expected 201 pre-response cancels to trip the breaker");
            } catch (Http2Exception e) {
                assertEquals(Http2ErrorCode.ENHANCE_YOUR_CALM, e.errorCode());
            }

            var served = new Http2Stream(3, conn,
                Map.<String, List<String>>of(), (s, in, o, h) -> {});
            served.writeResponseHeaders(true);
            for (int i = 0; i < 250; i++) {
                conn.notePeerReset(served);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void goAwayCarriesLastHandledStreamId() throws Exception {
        // A refused stream advances validation (lastSeen) but must not leak
        // into GOAWAY's last-stream-id: it was never processed and carries
        // an explicit retry signal.
        var executor = Executors.newSingleThreadExecutor();
        try (var socket = new Socket()) {
            var out = new ByteArrayOutputStream();
            var conn = new Http2Connection(
                socket,
                new ByteArrayInputStream(new byte[0]),
                out,
                executor,
                (stream, in, o, headers) -> {},
                0
            );
            setStreamCursor(conn, "lastSeenStreamId", 5);
            setStreamCursor(conn, "lastHandledStreamId", 3);
            conn.sendGoAway(Http2ErrorCode.NO_ERROR);

            byte[] bytes = out.toByteArray();
            assertEquals(17, bytes.length, "GOAWAY = 9-byte header + 8-byte body");
            int lastId = ((bytes[9] & 0xff) << 24) | ((bytes[10] & 0xff) << 16)
                | ((bytes[11] & 0xff) << 8) | (bytes[12] & 0xff);
            int code = ((bytes[13] & 0xff) << 24) | ((bytes[14] & 0xff) << 16)
                | ((bytes[15] & 0xff) << 8) | (bytes[16] & 0xff);
            assertEquals(3, lastId,
                "GOAWAY must report the last handled stream, not a refused one");
            assertEquals(0, code, "GOAWAY must carry NO_ERROR");
        } finally {
            executor.shutdownNow();
        }
    }

    private static void setStreamCursor(
            Http2Connection conn, String field, int value) throws Exception {
        var f = Http2Connection.class.getDeclaredField(field);
        f.setAccessible(true);
        f.setInt(conn, value);
    }
}
