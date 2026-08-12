package com.jujin.freeway.http.engine;

import com.jujin.freeway.http.body.BodyTooLargeException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.LongSupplier;

/**
 * Request-body reading state for one request: the raw stream, the bounded
 * wrapper, full-body caching, the size limit, and the drain decision that
 * controls keep-alive reuse.
 *
 * <p>Owns all body-size accounting so the connection layer only needs to
 * ask "was the body fully consumed?" and the transport-specific parser
 * (e.g. HTTP/1.1 chunked-prefix reclaim) stays out of the framing logic.</p>
 */
final class RequestBody {

    private final InputStream raw;
    private final long contentLength; // -1 = chunked or unknown
    private final boolean chunked;
    /** Read dynamically so a handler-raised limit applies mid-request. */
    private final LongSupplier maxBodySize;
    private InputStream bounded;
    private byte[] cached;
    private boolean limitExceeded;
    private final byte[] drainBuffer = new byte[2048];

    RequestBody(InputStream raw, long contentLength, boolean chunked,
                LongSupplier maxBodySize) {
        this.raw = raw;
        this.contentLength = contentLength;
        this.chunked = chunked;
        this.maxBodySize = maxBodySize;
    }

    boolean limitExceeded() {
        return limitExceeded;
    }

    /** Reads the entire body, enforcing the configured size limit. */
    byte[] readAll() throws IOException {
        if (cached == null) {
            cached = readLimited(stream());
        }
        return cached;
    }

    /**
     * Drains any unread body bytes so the connection can be reused.
     *
     * @return true if the body was fully consumed; false when the connection
     *         must not be reused (over-limit body, or draining failed)
     */
    boolean drain() {
        if (cached != null) return true;
        if (limitExceeded) return false;
        long limit = maxBodySize.getAsLong();
        if (contentLength > limit) return false;
        if (raw == null || (contentLength <= 0 && !chunked)) return true;
        try {
            InputStream remaining = stream();
            long drained = 0;
            int n;
            while ((n = remaining.read(drainBuffer)) >= 0) {
                drained += n;
                if (drained > limit) return false;
            }
            return true;
        } catch (IOException ignored) {
            // Best-effort drain — the connection closes if this fails.
            return false;
        }
    }

    private InputStream stream() {
        if (bounded != null) return bounded;
        if (chunked) {
            bounded = new ChunkedInputStream(raw);
        } else if (contentLength >= 0) {
            bounded = new FixedLengthInputStream(raw, contentLength);
        } else {
            return raw;
        }
        return bounded;
    }

    private byte[] readLimited(InputStream input) throws IOException {
        var out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            long limit = maxBodySize.getAsLong();
            if (total > limit - read) {
                limitExceeded = true;
                throw new BodyTooLargeException(limit);
            }
            out.write(buffer, 0, read);
            total += read;
        }
        return out.toByteArray();
    }
}
