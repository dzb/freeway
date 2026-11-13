package com.jujin.freeway.http.engine;

import com.jujin.freeway.http.body.BodyTooLargeException;
import com.jujin.freeway.http.internal.LimitedInputStream;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Request-body framing state for one request: the raw stream, the chunked or
 * fixed-length wrapper, and how many bytes have been consumed.
 *
 * <p>Size limiting and full-body caching live in {@code
 * HttpExchangeSupport} — the policy object every engine composes. This class
 * is the built-in engine's wire framing only, plus the drain decision that
 * controls keep-alive reuse.</p>
 */
final class RequestBody {

    private final InputStream raw;
    private final long contentLength; // -1 = chunked or unknown
    private final boolean chunked;
    private CountingInputStream framed;
    private final byte[] drainBuffer = new byte[2048];

    RequestBody(InputStream raw, long contentLength, boolean chunked) {
        this.raw = raw;
        this.contentLength = contentLength;
        this.chunked = chunked;
    }

    /** Total framed bytes read so far, so a drain can enforce the same
     *  ceiling the support object's limiter enforces on handler reads. */
    long consumed() {
        return framed == null ? 0 : framed.count;
    }

    /** Returns the transport-framed stream (chunked or fixed-length),
     *  counting every byte read for {@link #drain}. */
    InputStream stream() {
        if (framed == null) {
            framed = new CountingInputStream(frame());
        }
        return framed;
    }

    /**
     * Drains any unread body bytes so the connection can be reused.
     *
     * @param limit the body ceiling in force for this request (the support
     *        object's current {@code maxBodySize})
     * @return true if the body was fully consumed; false when the connection
     *         must not be reused (over-limit body, or draining failed)
     */
    boolean drain(long limit) {
        if (consumed() > limit) return false;
        if (contentLength > limit) return false;
        if (raw == null || (contentLength <= 0 && !chunked)) return true;
        // Bytes already consumed by the handler's limiter are still counted,
        // so this second limiter starts where the first one stopped.
        long remainingAllowance = limit - consumed();
        InputStream remaining = new LimitedInputStream(stream(),
            () -> remainingAllowance);
        try {
            while (remaining.read(drainBuffer) >= 0) {
                // Drain until EOF; a body past the ceiling fails the probe
                // and throws BodyTooLargeException.
            }
            return true;
        } catch (BodyTooLargeException e) {
            return false;
        } catch (IOException ignored) {
            // Best-effort drain — the connection closes if this fails.
            return false;
        }
    }

    private InputStream frame() {
        InputStream src = raw == null ? InputStream.nullInputStream() : raw;
        if (chunked) {
            return new ChunkedInputStream(src);
        }
        if (contentLength >= 0) {
            return new FixedLengthInputStream(src, contentLength);
        }
        return src;
    }

    /** Counts bytes crossing the framing boundary for {@link #drain}. */
    private static final class CountingInputStream extends FilterInputStream {
        private long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int n = in.read();
            if (n >= 0) count++;
            return n;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = in.skip(n);
            if (skipped > 0) count += skipped;
            return skipped;
        }
    }
}
