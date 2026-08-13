package com.jujin.freeway.http.engine.http2.hpack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.http.engine.http2.Http2ErrorCode;
import com.jujin.freeway.http.engine.http2.Http2Exception;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HPackContextTest {

    @Test
    void readIntThrowsOnTruncatedBlock() {
        // 0xFF at prefix=1 triggers multi-byte read but only 1 byte available
        assertThrows(ArrayIndexOutOfBoundsException.class,
                () -> HPackContext.readInt(new byte[]{(byte) 0xFF}, 0, 1),
                "Truncated HPACK block should throw, not silently return");
    }

    @Test
    void readIntOverflowThrowsCompressionError() {
        // Prefix byte 0x1F (mask 31) followed by continuation bytes that push
        // the value past 2^31-1: 31 + 127 + 127<<7 + 127<<14 + 127<<21 + 15<<28
        // = 4_294_967_326 > Integer.MAX_VALUE. Must fail with COMPRESSION_ERROR
        // instead of wrapping around (RFC 7541 §5.1: "This implies that values
        // must be less than 2^31").
        byte[] block = {
            (byte) 0x1F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0x0F
        };
        var ex = assertThrows(Http2Exception.class,
            () -> HPackContext.readInt(block, 0, 5));
        assertEquals(Http2ErrorCode.COMPRESSION_ERROR, ex.errorCode());
    }

    @Test
    void encodeStringHandlesLength128() {
        byte[] value = new byte[128];
        byte[] encoded = HPackContext.encodeString(value);
        assertEquals((byte) 0x7F, encoded[0],
                "Prefix for length >=127 must be 0x7F per HPACK spec");
        assertEquals(0x01, encoded[1] & 0xFF,
                "Continuation for 128 encodes remainder 1");
        assertEquals(2 + 128, encoded.length,
                "2-byte header + 128-byte payload");
    }

    @Test
    void encodeStringHandlesLength127() {
        byte[] value = new byte[127];
        byte[] encoded = HPackContext.encodeString(value);
        // 127 >= 127 (prefixMask) → long encoding: 2-byte header + data
        assertEquals((byte) 0x7F, encoded[0], "Prefix must be 0x7F");
        assertEquals(2 + 127, encoded.length,
                "127 needs 2-byte header (0x7F + 0x00) + 127 data = 129");
    }

    @Test
    void dynamicTableIsBoundedByMaxSize() throws IOException {
        HPackContext hpack = new HPackContext();
        hpack.decode(incrementalBlock(100, 100, 100));

        // 100 entries of 232 bytes each exceed the 4096-byte table limit;
        // only the newest entries may remain.
        assertNotNull(hpack.get(62), "Newest dynamic table entry must be stored");
        assertNull(hpack.get(62 + 30), "Old entries must be evicted");
    }

    @Test
    void peerTableSizeCannotExceedLocalHardCap() throws IOException {
        HPackContext hpack = new HPackContext();
        hpack.setMaxDynamicTableSize(1024 * 1024);
        hpack.decode(incrementalBlock(400, 100, 100));

        assertNull(hpack.get(62 + 300),
            "Inbound dynamic table must remain below the local hard cap");
    }

    @Test
    void oversizedFieldIsNotAddedToDynamicTable() throws IOException {
        HPackContext hpack = new HPackContext();
        hpack.decode(incrementalBlock(1, 3000, 3000));

        assertNull(hpack.get(62),
            "An entry larger than the table capacity must not be stored");
    }

    @Test
    void decodedHeaderListSizeIsBounded() {
        HPackContext hpack = new HPackContext();
        assertThrows(Http2Exception.class,
            () -> hpack.decode(incrementalBlock(2, 100, 100), 200));
    }

    @Test
    void writeResponseHeadersRejectsOversizedHeaders() {
        HPackContext hpack = new HPackContext();
        var out = new ByteArrayOutputStream();
        // A single oversized header value must be rejected before encoding
        // (budget check runs pre-encode, so no large block is allocated).
        Map<String, List<String>> headers = Map.of(
            "x-big", List.of("a".repeat(70 * 1024)));
        assertThrows(IOException.class, () ->
            hpack.writeResponseHeaders(headers, out, 1, true));
    }

    @Test
    void writeResponseHeadersWritesFrame() throws IOException {
        HPackContext hpack = new HPackContext();
        var out = new ByteArrayOutputStream();
        hpack.writeResponseHeaders(Map.of("x-foo", List.of("bar")), out, 1, false);
        assertTrue(out.size() > 9, "9-byte frame header + encoded headers");
    }

    @Test
    void huffmanMatchesRfcTestVector() {
        // RFC 7541 C.4.1 — the canonical example request string.
        byte[] encoded = Huffman.encode("www.example.com");
        assertEquals(12, encoded.length);
        assertEquals("f1e3c2e5f23a6ba0ab90f4ff",
            HexFormat.of().formatHex(encoded));
    }

    @Test
    void integerEncodingMatchesRfc7541Section5Examples() {
        // RFC 7541 §5.1 worked examples.
        assertArrayEquals(new byte[]{(byte) 0x0A},
            HPackContext.encodeIntValue(10, 5), "N=5, I=10 → 0x0A");
        assertArrayEquals(new byte[]{(byte) 0x1F, (byte) 0x9A, (byte) 0x0A},
            HPackContext.encodeIntValue(1337, 5), "N=5, I=1337 → 1F 9A 0A");
        assertArrayEquals(new byte[]{(byte) 0x2A},
            HPackContext.encodeIntValue(42, 8), "N=8, I=42 → 0x2A");
        assertArrayEquals(new byte[]{(byte) 0xFF, (byte) 0xBA, (byte) 0x08},
            HPackContext.encodeIntValue(1337, 8), "N=8, I=1337 → FF BA 08");
    }

    @Test
    void stringEncodingSetsHuffmanFlag() {
        // RFC 7541 §5.2: H bit is the high bit of the length prefix byte.
        byte[] bytes = "www.example.com".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertEquals(15, bytes.length, "www.example.com is 15 bytes");
        byte[] plain = HPackContext.encodeString(bytes, 7);
        assertEquals(0x0F, plain[0] & 0xFF, "len=15, H=0");

        byte[] huffman = HPackContext.encodeStringHuffman(bytes);
        // H=1, len=12 (RFC 7541 C.4.1 Huffman output is 12 bytes)
        assertEquals(0x8C, huffman[0] & 0xFF, "H=1, len=12");
        assertEquals("f1e3c2e5f23a6ba0ab90f4ff",
            HexFormat.of().formatHex(
                Arrays.copyOfRange(huffman, 1, huffman.length)),
            "RFC 7541 C.4.1 canonical Huffman bytes");
    }

    @Test
    void huffmanEncodeDecodeRoundTrip() throws IOException {
        for (String s : List.of(
                "www.example.com", "custom-key", "custom-value",
                "Hello World! %~+/#", "abcdefghijklmnopqrstuvwxyz0123456789",
                "×", "", "a", "path/to/resource?q=1&x=2")) {
            assertEquals(s, Huffman.decode(Huffman.encode(s)),
                "Huffman round-trip failed for: " + s);
        }
    }

    private static byte[] incrementalBlock(int count, int nameLen, int valueLen) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] name = new byte[nameLen];
        Arrays.fill(name, (byte) 'x');
        byte[] value = new byte[valueLen];
        Arrays.fill(value, (byte) 'y');
        for (int i = 0; i < count; i++) {
            out.write(0x40); // literal with incremental indexing, new name
            writeString(out, name);
            writeString(out, value);
        }
        return out.toByteArray();
    }

    private static void writeString(ByteArrayOutputStream out, byte[] bytes) {
        int length = bytes.length;
        if (length < 127) {
            out.write(length);
        } else {
            out.write(0x7F);
            int remaining = length - 127;
            while (remaining >= 128) {
                out.write((remaining & 0x7F) | 0x80);
                remaining >>>= 7;
            }
            out.write(remaining);
        }
        out.writeBytes(bytes);
    }
}
