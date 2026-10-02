package com.jujin.freeway.http.internal;

import com.jujin.freeway.http.body.BodyTooLargeException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The one body-size limiter for engine-owned bodies: a stream that stops at
 * {@code limit} and throws {@link BodyTooLargeException} past it.
 *
 * <p>Single implementation on purpose. {@code AbstractHttpContext#readBody}
 * (the adapter seam) and {@code RequestBody} (the built-in engine) both need
 * "read this body, refuse to exceed the configured size", and having two of
 * them meant the documented cross-engine guarantee — that 413 accounting is
 * identical everywhere — was enforced by nothing. The two happened to agree
 * on every case {@code BodyLimitParityTest} explores, so this was a latent
 * divergence rather than a live bug; what changed is that the guarantee is now
 * structural instead of accidental.
 *
 * <p>The scope is the bodies the engine hands over. A multipart part has its
 * own ceiling ({@code MultipartForm}'s per-part limit, which fails with an
 * {@code IOException} rather than a 413), and an adapter that enforces a
 * native limit before this stream sees the body — Undertow's
 * {@code MAX_ENTITY_SIZE}, fixed at server start — still refuses past that
 * limit, reporting the size it was configured with. Narrowing
 * {@code maxBodySize} per request therefore works on every path; raising it
 * only works where nothing upstream has already capped the read.
 *
 * <p>Lives in {@code http.internal} rather than {@code engine} because the
 * shared party is the root package's {@code AbstractHttpContext}, and Java
 * has no sub-package visibility — the same reason {@code HttpUtils} sits here.
 *
 * <p>Two details that a naive read loop gets wrong, and that the engines'
 * 413 behaviour depends on:
 *
 * <ul>
 *   <li><b>The limit is read per read, not captured once</b>, so a filter that
 *       raises {@code maxBodySize} mid-request takes effect immediately rather
 *       than at the next request.</li>
 *   <li><b>Reaching the limit is not yet a failure.</b> The stream probes for
 *       one more byte: EOF means the body was exactly at the limit and the read
 *       ends normally, while a real byte means it was over. Without the probe,
 *       a body of exactly the limit either failed spuriously or silently
 *       truncated.</li>
 * </ul>
 */
public final class LimitedInputStream extends InputStream {

    private final InputStream in;
    private final LongSupplier maxBodySize;
    private long total;
    /** EOF observed on the bounded stream — lets a drain stop without re-reading. */
    private boolean eof;
    /** True once the body was found to exceed the limit. */
    private boolean limitExceeded;
    private final byte[] oneByte = new byte[1];

    public LimitedInputStream(InputStream in, LongSupplier maxBodySize) {
        this.in = in;
        this.maxBodySize = maxBodySize;
    }

    /** Whether EOF was observed on the bounded stream — lets a drain stop without re-reading. */
    public boolean eof() {
        return eof;
    }

    /** True once the body was found to exceed the limit. */
    public boolean limitExceeded() {
        return limitExceeded;
    }

    @Override
    public int read() throws IOException {
        int n = read(oneByte, 0, 1);
        return n < 0 ? -1 : oneByte[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        // The InputStream contract's own argument checks, first and
        // unconditionally. The len == 0 shortcut below returns without touching
        // the stream, so without these an out-of-range off or a null array would
        // be reported as end-of-body instead of the IndexOutOfBoundsException /
        // NullPointerException every caller of read(byte[],int,int) expects.
        Objects.checkFromIndexSize(off, len, b.length);
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
