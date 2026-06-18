package com.jujin.freeway.commons.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Lightweight {@link InputStream} utilities.
 */
public final class InputStreams {

    private InputStreams() {}

    /**
     * Wraps the stream so that reads beyond {@code maxBytes} throw an
     * {@link IOException}.  Use when passing the stream to a consumer that
     * does not accept a size limit (e.g. {@code Properties.load()}).
     *
     * @param label used in the error message (e.g. a resource path)
     */
    public static InputStream bounded(InputStream stream, long maxBytes, String label) {
        return new InputStream() {
            private long count;

            @Override
            public int read() throws IOException {
                if (count >= maxBytes) {
                    int extra = stream.read();
                    if (extra == -1) return -1;
                    throw tooLarge();
                }
                int read = stream.read();
                if (read >= 0) count++;
                return read;
            }

            @Override
            public int read(byte[] bytes, int off, int len) throws IOException {
                Objects.checkFromIndexSize(off, len, bytes.length);
                if (len == 0) return 0;
                if (count >= maxBytes) {
                    int extra = stream.read();
                    if (extra == -1) return -1;
                    throw tooLarge();
                }
                int allowed = (int) Math.min(len, maxBytes - count);
                int read = stream.read(bytes, off, allowed);
                if (read > 0) count += read;
                return read;
            }

            private IOException tooLarge() {
                return new IOException(label + " exceeds " + maxBytes + " bytes");
            }
        };
    }

    /**
     * Reads all bytes from the stream, capping at {@code maxBytes}.
     *
     * @param label   used in the error message (e.g. a resource path)
     * @throws IOException if the stream exceeds the limit
     */
    public static byte[] readBytes(InputStream in, long maxBytes, String label)
            throws IOException {
        var out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            if (total > maxBytes - read) {
                throw new IOException(
                        label + " too large (max " + maxBytes + " bytes)");
            }
            out.write(buffer, 0, read);
            total += read;
        }
        return out.toByteArray();
    }
}
