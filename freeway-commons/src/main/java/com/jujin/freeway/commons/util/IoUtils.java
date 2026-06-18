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
     * @param stream the input stream to wrap (must not be null)
     * @param maxBytes the maximum number of bytes allowed to read (must be non-negative)
     * @param label used in the error message (e.g. a resource path)
     * @return a bounded input stream
     * @throws IllegalArgumentException if maxBytes is negative or stream is null
     */
    public static InputStream bounded(InputStream stream, long maxBytes, String label) {
        if (stream == null) {
            throw new IllegalArgumentException("stream must not be null");
        }
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must be non-negative: " + maxBytes);
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
                return new IOException(label + " exceeds " + maxBytes + " bytes (read " + count + " bytes)");
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

    /**
     * Checks if a single path segment contains directory traversal patterns.
     * <p>
     * Detects the following dangerous patterns:
     * <ul>
     *   <li>Parent directory references (".." or "..\")</li>
     *   <li>Null bytes that could truncate paths</li>
     *   <li>URL-encoded traversal sequences (e.g., "%2e%2e")</li>
     *   <li>Malformed URL encoding that may indicate evasion attempts</li>
     * </ul>
     *
     * @param seg the path segment to check (typically one component between slashes)
     * @return {@code true} if the segment contains any traversal pattern, {@code false} otherwise
     */
    public static boolean isPathTraversalSegment(String seg) {
        // Check for direct parent directory references
        if ("..".equals(seg) || seg.startsWith("..\\")) return true;
        
        // Reject segments containing null bytes
        if (seg.contains("\0")) return true;
        
        // Decode URL encoding and recursively check for traversal patterns
        try {
            String decoded = URLDecoder.decode(seg, StandardCharsets.UTF_8);
            if (!decoded.equals(seg) && containsPathTraversal(decoded)) return true;
        } catch (IllegalArgumentException e) {
            // Malformed encoding is treated as suspicious
            return true;
        }
        return false;
    }
}
