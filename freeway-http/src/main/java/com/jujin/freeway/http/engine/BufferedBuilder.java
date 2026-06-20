package com.jujin.freeway.http.engine;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Growable byte buffer for reading HTTP lines from a raw InputStream.
 * Accumulates ISO-8859-1 bytes (the HTTP wire format) and converts
 * to {@code String} on demand. Not thread-safe.
 */
final class BufferedBuilder {

    private byte[] buffer;
    private int count;

    BufferedBuilder(int capacity) {
        buffer = new byte[capacity];
    }

    boolean isEmpty() {
        return count == 0;
    }

    void append(char c) {
        if (count == buffer.length) {
            buffer = Arrays.copyOf(buffer, buffer.length * 2);
        }
        buffer[count++] = (byte) c;
    }

    /**
     * Returns the trimmed content as an ISO-8859-1 string and resets the buffer.
     */
    String trimmed() {
        int start = 0;
        while (start < count && buffer[start] == ' ') {
            start++;
        }
        int end = count;
        while (end > 0 && buffer[end - 1] == ' ') {
            end--;
        }
        count = 0;
        return new String(buffer, start, end - start, StandardCharsets.ISO_8859_1);
    }
}
