package com.jujin.freeway.commons.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Lightweight {@link InputStream} utilities.
 */
public final class InputStreams {

    private InputStreams() {}

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
