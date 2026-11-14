package com.jujin.freeway.http.engine.http2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Property baseline for HPACK: a valid header map survives the encoder's frame
 * split and the decoder's table/Huffman path with exactly the same fields, for
 * random header names, values and frame sizes.
 */
class HPackPropertyTest {

    @Test
    void randomHeaderBlocksRoundTripThroughTheWireFrames() throws Exception {
        Random random = new Random(0x48AC1L);
        for (int iteration = 0; iteration < 250; iteration++) {
            Map<String, List<String>> headers = new LinkedHashMap<>();
            headers.put(":status", List.of("200"));
            int count = 1 + random.nextInt(6);
            for (int i = 0; i < count; i++) {
                String name = "x-" + token(random, 1 + random.nextInt(12));
                List<String> values = new ArrayList<>();
                int valueCount = 1 + random.nextInt(3);
                for (int v = 0; v < valueCount; v++) {
                    values.add(value(random, random.nextInt(24)));
                }
                headers.put(name, values);
            }

            int maxFrameSize = 32 + random.nextInt(2_048);
            List<byte[]> frames = new HPackContext()
                .encodeResponseHeaders(headers, 3, false, maxFrameSize);
            byte[] block = reassemble(frames);
            List<Http2HeaderField> decoded = new HPackContext().decode(block);

            List<String> expected = new ArrayList<>();
            headers.forEach((name, values) ->
                values.forEach(value -> expected.add(name + "\0" + value)));
            List<String> actual = decoded.stream()
                .map(field -> field.name + "\0" + field.value)
                .toList();
            assertEquals(expected, actual,
                "iteration " + iteration + " headers=" + headers
                    + " frameSize=" + maxFrameSize);
        }
    }

    @Test
    void randomHuffmanStringsRoundTrip() throws Exception {
        Random random = new Random(0x4AFF);
        for (int i = 0; i < 500; i++) {
            String value = value(random, random.nextInt(40));
            assertEquals(value, Huffman.decode(Huffman.encode(value)),
                "iteration " + i + " value=" + value);
        }
    }

    private static byte[] reassemble(List<byte[]> frames) {
        int length = 0;
        for (byte[] frame : frames) {
            length += frame.length - 9; // 9-byte HTTP/2 frame header
        }
        byte[] block = new byte[length];
        int offset = 0;
        for (byte[] frame : frames) {
            int payload = frame.length - 9;
            System.arraycopy(frame, 9, block, offset, payload);
            offset += payload;
        }
        return block;
    }

    private static String token(Random random, int length) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return value.toString();
    }

    private static String value(Random random, int length) {
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 -_/;=,.:";
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return value.toString();
    }
}
