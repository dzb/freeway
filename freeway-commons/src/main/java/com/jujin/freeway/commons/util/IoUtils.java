package com.jujin.freeway.commons.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class IoUtils {

    private IoUtils() {}

    // ==================================================================
    //  Stream helpers
    // ==================================================================

    /**
     * Wraps the stream so that reads beyond {@code maxBytes} throw an
     * {@link IOException}. Use when passing the stream to a consumer that
     * does not accept a size limit (e.g. {@code Properties.load}).
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
                throw new IOException(label + " too large (max " + maxBytes + " bytes)");
            }
            out.write(buffer, 0, read);
            total += read;
        }
        return out.toByteArray();
    }

    // ==================================================================
    //  Path helpers
    // ==================================================================

    /** Normalizes a path: ensures leading {@code /}, strips trailing {@code /}. */
    public static String normalizePath(String path) {
        String value = Strings.blankToNull(path);
        if (value == null || "/".equals(value)) return "/";
        value = value.startsWith("/") ? value : "/" + value;
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** Splits a path into non-empty segments. */
    public static String[] splitPath(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return new String[0];
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) return new String[0];
        return normalized.split("/");
    }

    /** Returns {@code true} if the path contains {@code ..}, null bytes, or URL-encoded traversal. */
    public static boolean containsPathTraversal(String path) {
        for (String seg : path.split("/")) {
            if (isPathTraversalSegment(seg)) return true;
        }
        return false;
    }

    public static boolean isPathTraversalSegment(String seg) {
        if ("..".equals(seg) || seg.startsWith("..\\")) return true;
        if (seg.contains("\0")) return true;
        try {
            String decoded = URLDecoder.decode(seg, StandardCharsets.UTF_8);
            if (!decoded.equals(seg) && containsPathTraversal(decoded)) return true;
        } catch (IllegalArgumentException e) {
            return true;
        }
        return false;
    }
}
