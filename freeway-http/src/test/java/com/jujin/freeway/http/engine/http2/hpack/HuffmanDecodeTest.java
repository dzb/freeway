package com.jujin.freeway.http.engine.http2.hpack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.http.engine.http2.util.Http2ErrorCode;
import com.jujin.freeway.http.engine.http2.util.Http2Exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the table-driven HPACK Huffman decoder: correctness
 * across the whole code table, an explicit CPU-DoS budget for a ~64 KB
 * attacker-sized block, and the malformed-input checks (bad padding bits,
 * EOS symbol) that must keep failing with COMPRESSION_ERROR.
 */
class HuffmanDecodeTest {

    @Test
    void decodesLargeAllAsciiBlockWithinTimeBudget() throws IOException {
        // 100 KB of 'a' — the 5-bit 'a' code yields a ~62 KB Huffman block,
        // the size of an attacker-chosen 64 KB header block. The previous
        // O(257²)-per-symbol scan took tens of seconds on this input; the
        // table-driven lookup must finish in milliseconds. The 2 s budget is
        // deliberately generous for CI jitter.
        byte[] payload = new byte[100 * 1024];
        Arrays.fill(payload, (byte) 'a');
        byte[] encoded = Huffman.encode(payload);
        assertTrue(encoded.length >= 60 * 1024,
            "expected a ~62 KB encoded block, got " + encoded.length + " bytes");

        long start = System.nanoTime();
        String decoded = Huffman.decode(encoded);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(new String(payload, StandardCharsets.ISO_8859_1), decoded,
            "decoded content must match the encoded payload");
        assertTrue(elapsedMillis < 2_000,
            "decoding a 64 KB block must complete well under 2 s, took "
                + elapsedMillis + " ms");
    }

    @Test
    void allByteValuesRoundTrip() throws IOException {
        // Every byte value 0..255 must survive encode → decode; exercises the
        // full code table including the longest 30-bit codes and every length
        // between 5 and 30.
        byte[] payload = new byte[256];
        for (int i = 0; i < 256; i++) {
            payload[i] = (byte) i;
        }
        byte[] encoded = Huffman.encode(payload);
        String decoded = Huffman.decode(encoded);
        assertEquals(new String(payload, StandardCharsets.ISO_8859_1), decoded);
    }

    @Test
    void badPaddingBitsStillThrowCompressionError() {
        // 'a' encodes to the 5-bit code 0b00011 padded with three 1 bits →
        // 0x1F. Clearing the final padding bit (0x1E) must be rejected with
        // COMPRESSION_ERROR (RFC 7541 §5.2).
        byte[] bad = {(byte) 0x1E};
        var ex = assertThrows(Http2Exception.class, () -> Huffman.decode(bad));
        assertEquals(Http2ErrorCode.COMPRESSION_ERROR, ex.errorCode());
    }

    @Test
    void eosSymbolStillThrowsCompressionError() {
        // RFC 7541 §5.2: an EOS symbol (256, 30-bit code 0x3fffffff) in the
        // encoded stream must abort decoding with COMPRESSION_ERROR.
        byte[] eos = HexFormat.of().parseHex("ffffffff");
        var ex = assertThrows(Http2Exception.class, () -> Huffman.decode(eos));
        assertEquals(Http2ErrorCode.COMPRESSION_ERROR, ex.errorCode());
    }
}
