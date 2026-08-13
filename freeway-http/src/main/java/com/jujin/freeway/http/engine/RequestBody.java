package com.jujin.freeway.http.engine;

import com.jujin.freeway.http.body.BodyTooLargeException;

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
    private InputStream framed;
    private LimitedInputStream limited;
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
            cached = stream().readAllBytes();
        }
        return cached;
    }

    /**
     * Returns the request body as a streaming input. The returned stream
     * enforces the dynamic size limit on every read and throws
     * {@link BodyTooLargeException} once the body exceeds it.
     */
    InputStream stream() {
        if (limited == null) {
            limited = new LimitedInputStream(framed());
        }
        return limited;
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
        InputStream remaining = stream();
        if (remaining instanceof LimitedInputStream li && li.eof) return true;
        try {
            while (remaining.read(drainBuffer) >= 0) {
                // Keep draining until EOF; the bounded stream enforces the
                // size limit and throws BodyTooLargeException when exceeded.
            }
            return true;
        } catch (BodyTooLargeException e) {
            return false;
        } catch (IOException ignored) {
            // Best-effort drain — the connection closes if this fails.
            return false;
        }
    }

    /** Returns the transport-framed stream (chunked or fixed-length). */
    private InputStream framed() {
        if (framed != null) return framed;
        InputStream src = raw == null ? InputStream.nullInputStream() : raw;
        if (chunked) {
            framed = new ChunkedInputStream(src);
        } else if (contentLength >= 0) {
            framed = new FixedLengthInputStream(src, contentLength);
        } else {
            framed = src;
        }
        return framed;
    }

    /**
     * Bounds the framed request body to the dynamically configured size
     * limit. Once the body reaches the limit, the next read probes for one
     * extra byte so an exactly-at-limit body ends at EOF while an over-limit
     * body fails fast with {@link BodyTooLargeException}.
     */
    private final class LimitedInputStream extends InputStream {
        private final InputStream in;
        long total = 0;
        boolean eof;
        private final byte[] oneByte = new byte[1];

        LimitedInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            int n = read(oneByte, 0, 1);
            return n < 0 ? -1 : oneByte[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            long limit = maxBodySize.getAsLong();
            long remaining = limit - total;
            if (remaining <= 0) {
                // At the limit: distinguish a clean EOF from an over-limit body.
                int probe = in.read();
                if (probe < 0) {
                    eof = true;
                    return -1;
                }
                limitExceeded = true;
                throw new BodyTooLargeException(limit);
            }
            if (len > remaining) {
                len = (int) remaining;
            }
            int n = in.read(b, off, len);
            if (n < 0) {
                eof = true;
            } else if (n > 0) {
                total += n;
            }
            return n;
        }

        @Override
        public int available() throws IOException {
            long limit = maxBodySize.getAsLong();
            long remaining = Math.max(0, limit - total);
            return (int) Math.min(in.available(), remaining);
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
