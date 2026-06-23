package com.jujin.freeway.commons.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public final class ByteStreams {

    private ByteStreams() {}

    // ==================================================================
    //  Stream helpers
    // ==================================================================

    /**
     * Wraps the stream so that reads beyond {@code maxBytes} throw an
     * {@link IOException}. Use when passing the stream to a consumer that
     * does not accept a size limit (e.g. {@code Properties.load}).
     *
     * @param stream the input stream to wrap (must not be null)
     * @param maxBytes the maximum number of bytes allowed to read (must be non-negative)
     * @param label used in the error message (e.g. a resource path)
     * @return a bounded input stream
     * @throws IllegalArgumentException if maxBytes is negative or stream is null
     */
    public static InputStream bounded(
        InputStream stream,
        long maxBytes,
        String label
    ) {
        if (stream == null) {
            throw new IllegalArgumentException("stream must not be null");
        }
        if (maxBytes < 0) {
            throw new IllegalArgumentException(
                "maxBytes must be non-negative: " + maxBytes
            );
        }
        return new InputStream() {
            private long count;

            @Override
            public int read() throws IOException {
                if (count >= maxBytes) {
                    throw tooLarge();
                }
                int read = stream.read();
                if (read >= 0) {
                    count++;
                }
                return read;
            }

            @Override
            public int read(byte[] bytes, int off, int len) throws IOException {
                Objects.checkFromIndexSize(off, len, bytes.length);
                if (len == 0) {
                    return 0;
                }
                if (count >= maxBytes) {
                    throw tooLarge();
                }
                long remaining = maxBytes - count;
                int allowed = (int) Math.min(len, remaining);
                int read = stream.read(bytes, off, allowed);
                if (read > 0) {
                    count += read;
                }
                return read;
            }

            private IOException tooLarge() {
                return new IOException(
                    label +
                        " exceeds " +
                        maxBytes +
                        " bytes (read " +
                        count +
                        " bytes)"
                );
            }
        };
    }

    /**
     * Reads all bytes from the stream, capping at {@code maxBytes}.
     *
     * @param label used in the error message (e.g. a resource path)
     * @throws IOException if the stream exceeds the limit
     */
    public static byte[] readBytes(InputStream in, long maxBytes, String label)
        throws IOException {
        var out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            if (read == 0) continue;
            if (total > maxBytes - read) {
                throw new IOException(
                    label + " too large (max " + maxBytes + " bytes)"
                );
            }
            out.write(buffer, 0, read);
            total += read;
        }
        return out.toByteArray();
    }
}
