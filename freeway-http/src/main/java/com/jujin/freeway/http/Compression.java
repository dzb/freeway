package com.jujin.freeway.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Accept-Encoding negotiation and gzip primitives for the HTTP SPI. Kept in the
 * root package next to {@link MediaTypes} so the built-in engine and the
 * transport adapters share one implementation instead of each carrying its own
 * copy of the q-value scan.
 *
 * <p>Semantics: an absent {@code Accept-Encoding} header means "no preference"
 * and does not compress; {@code gzip} enables compression, {@code gzip;q=0}
 * refuses it, and any other {@code q} value enables it. A malformed {@code q}
 * parameter is treated as "not zero", i.e. the client accepts gzip.
 */
public final class Compression {

    private Compression() {}

    /**
     * True when the request's Accept-Encoding header lines allow a gzip
     * response. Multiple header lines are each consulted, so
     * {@code Accept-Encoding: br} plus {@code Accept-Encoding: gzip} accepts
     * gzip (RFC 9110 allows the field to be repeated).
     */
    public static boolean acceptsGzip(List<String> acceptEncodingValues) {
        if (acceptEncodingValues == null) return false;
        for (String value : acceptEncodingValues) {
            if (acceptsGzip(value)) return true;
        }
        return false;
    }

    /** True when one Accept-Encoding header value (comma-separated) allows gzip. */
    public static boolean acceptsGzip(String acceptEncodingValue) {
        if (acceptEncodingValue == null) return false;
        for (String part : acceptEncodingValue.split(",")) {
            String token = part.trim();
            int q = token.indexOf(';');
            String name = q < 0 ? token : token.substring(0, q).trim();
            if ("gzip".equalsIgnoreCase(name)) {
                if (q < 0) return true;
                return !qValueIsZero(token.substring(q + 1));
            }
        }
        return false;
    }

    /** True when a q-value parameter list sets {@code q=0}, refusing the encoding. */
    private static boolean qValueIsZero(String params) {
        for (String part : params.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && "q".equalsIgnoreCase(kv[0].trim())) {
                try {
                    return Double.parseDouble(kv[1].trim()) == 0.0;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
        }
        return false;
    }

    /** GZIP-compresses the data. */
    public static byte[] gzip(byte[] data) throws IOException {
        var out = new ByteArrayOutputStream(Math.max(64, data.length / 2));
        try (var gzip = new GZIPOutputStream(out)) {
            gzip.write(data);
        }
        return out.toByteArray();
    }
}
