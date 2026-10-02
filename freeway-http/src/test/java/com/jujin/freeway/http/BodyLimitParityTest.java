package com.jujin.freeway.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.http.body.BodyTooLargeException;
import com.jujin.freeway.http.internal.LimitedInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two body-size paths must agree, because {@code HttpEngine}'s contract
 * and AGENTS.md both promise it: "413 accounting is identical across
 * engines".
 *
 * <p><b>This is now a regression guard for a past shape, not a comparison of
 * two live implementations.</b> It was written when the built-in engine bounded
 * bodies through {@code RequestBody} and the adapter seam exposed
 * {@link AbstractHttpContext#readBody} — a second implementation that
 * {@code HttpEngine}'s javadoc and AGENTS.md both named as <em>the</em> shared
 * one while nothing in the framework called it. Two independent loops, one
 * documented guarantee, and the two happened to agree on every case here, so the
 * duplication was a latent divergence rather than a live bug.
 *
 * <p>{@code readBody} is now a one-line delegate to {@code LimitedInputStream},
 * the limiter the engine itself runs, so {@link #viaEngine} and
 * {@link #viaAdapterHelper} drive the same object and the case-by-case
 * comparison below has become an assertion about the delegation rather than
 * about two implementations. It is kept in that shape on purpose: should the
 * delegate ever be re-inlined, these cases are what would notice.
 */
class BodyLimitParityTest {

    private static byte[] latin(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** The built-in engine's limiter — the same one {@code RequestBody} uses. */
    private static byte[] viaEngine(long limit, String body) throws IOException {
        return new LimitedInputStream(
            new ByteArrayInputStream(latin(body)), () -> limit).readAllBytes();
    }

    /** The adapter seam: the shared helper an engine adapter is told to call. */
    private static byte[] viaAdapterHelper(long limit, String body) throws IOException {
        StubHttpContext ctx = new StubHttpContext();
        ctx.setMaxBodySize(limit);
        return ctx.readBody(new ByteArrayInputStream(latin(body)));
    }

    /**
     * Reads through a stream that hands out small chunks, because that is the
     * shape a socket produces and the one a naive limiter gets wrong.
     *
     * <p>{@code readAllBytes} on an in-memory stream hands over the whole body
     * in one call, which hides any per-read arithmetic error; the parity check
     * therefore drives the stream the way a handler would. (The old helper's
     * check was {@code total > limit - read}, which is {@code total + read >
     * limit} — "over the limit rejects" — so it did <em>not</em> have the bug
     * an earlier draft of this comment claimed; chunking is here to keep the
     * equivalence honest, not to reproduce one.)
     */
    private static byte[] viaEngineChunked(long limit, String body, int chunk) throws IOException {
        return new LimitedInputStream(
            new DripFeed(latin(body), chunk), () -> limit).readAllBytes();
    }

    private static byte[] viaAdapterHelperChunked(long limit, String body, int chunk) throws IOException {
        StubHttpContext ctx = new StubHttpContext();
        ctx.setMaxBodySize(limit);
        return ctx.readBody(new DripFeed(latin(body), chunk));
    }

    /**
     * The limiter honours {@link InputStream#read(byte[], int, int)}'s argument
     * contract, not just its limit.
     *
     * <p>The argument bounds are checked first
     * ({@code Objects.checkFromIndexSize}), then the {@code len == 0} shortcut,
     * and only then the limit — so a request already at its limit answers this
     * call's probe with EOF ({@code -1}). Without the explicit bounds check an
     * out-of-range {@code off}, a {@code len} past the array end, or a null array
     * was reported as end-of-body instead of the IndexOutOfBoundsException /
     * NullPointerException every caller expects — and this type is {@code public}
     * in a package adapters are told to reach for, so third-party code calls it
     * directly.
     */
    @Test
    void readValidatesItsArgumentsTheWayInputStreamDoes() throws IOException {
        for (LimitedInputStream stream : List.of(
            new LimitedInputStream(new ByteArrayInputStream(new byte[8]), () -> 100L),
            // at the limit: the early-return path is where an unchecked
            // out-of-range offset would have been reported as EOF
            new LimitedInputStream(new ByteArrayInputStream(new byte[8]), () -> 0L))) {

            byte[] buf = new byte[4];
            assertThrows(IndexOutOfBoundsException.class,
                () -> stream.read(buf, 99, 5), "off past the end");
            assertThrows(IndexOutOfBoundsException.class,
                () -> stream.read(buf, 2, 5), "off + len past the end");
            assertThrows(IndexOutOfBoundsException.class,
                () -> stream.read(buf, 0, 5), "len past the end");
            assertThrows(IndexOutOfBoundsException.class,
                () -> stream.read(buf, 99, 0),
                "a zero length does not excuse an out-of-range offset");
            assertThrows(NullPointerException.class,
                () -> stream.read(null, 0, 3), "null array");
            assertEquals(0, stream.read(buf, 0, 0),
                "a zero-length read at a valid offset is legal and returns 0");
        }
    }

    /** Yields at most {@code chunk} bytes per read, like a socket does. */
    private static final class DripFeed extends InputStream {
        private final byte[] data;
        private final int chunk;
        private int pos;

        DripFeed(byte[] data, int chunk) {
            this.data = data;
            this.chunk = chunk;
        }

        @Override
        public int read() {
            return pos < data.length ? data[pos++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (pos >= data.length) {
                return -1;
            }
            int n = Math.min(Math.min(len, chunk), data.length - pos);
            System.arraycopy(data, pos, b, off, n);
            pos += n;
            return n;
        }
    }

    @Test
    void bothPathsAgreeAcrossTheLimitBoundary() throws Exception {
        int[] limits = {1, 2, 3, 4, 5, 8, 10};
        int[] lengths = {0, 1, 2, 3, 4, 5, 6, 9, 11};

        for (int limit : limits) {
            for (int length : lengths) {
                String body = "x".repeat(length);
                String where = "limit=" + limit + " length=" + length;

                byte[] engine = readQuietly(() -> viaEngine(limit, body));
                byte[] helper = readQuietly(() -> viaAdapterHelper(limit, body));

                assertEquals(length > limit, engine == null,
                    "engine path wrong for " + where);
                assertEquals(engine == null, helper == null,
                    "the two body-limit paths disagree for " + where
                        + " — the cross-engine 413 guarantee is documented in "
                        + "HttpEngine and AGENTS.md, so this is a real divergence");
                if (engine != null) {
                    assertArrayEquals(engine, helper,
                        "accepted bodies must be byte-identical for " + where);
                }
            }
        }
    }

    @Test
    void bothPathsAgreeUnderChunkedReads() throws Exception {
        // A handler does not always drain the body in one call, and a socket
        // hands bytes back in whatever sizes arrive. The boundary only holds
        // if both paths behave the same when the reads land a few bytes at a
        // time, so drive them that way.
        for (int chunk : new int[]{1, 2, 3}) {
            for (int limit : new int[]{1, 2, 5}) {
                for (int length = Math.max(0, limit - 1); length <= limit + 2; length++) {
                    String body = "x".repeat(length);
                    String where = "chunk=" + chunk + " limit=" + limit + " length=" + length;

                    byte[] engine = readQuietly(() -> viaEngineChunked(limit, body, chunk));
                    byte[] helper = readQuietly(() -> viaAdapterHelperChunked(limit, body, chunk));

                    assertEquals(engine == null, helper == null,
                        "the two paths disagree for " + where);
                    if (engine != null) {
                        assertArrayEquals(engine, helper, "accepted bytes differ for " + where);
                    }
                }
            }
        }
    }

    /** Returns the read result, or null when the body was rejected. */
    private static byte[] readQuietly(ThrowingSupplier read) {
        try {
            return read.get();
        } catch (BodyTooLargeException e) {
            return null;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        byte[] get() throws IOException;
    }

    @Test
    void exactlyAtLimitIsAcceptedByBoth() throws Exception {
        assertEquals(5, viaEngine(5, "hello").length);
        assertEquals(5, viaAdapterHelper(5, "hello").length);
    }

    @Test
    void oneByteOverLimitIsRejectedByBoth() {
        assertThrows(BodyTooLargeException.class, () -> viaEngine(5, "hello!"));
        assertThrows(BodyTooLargeException.class, () -> viaAdapterHelper(5, "hello!"));
    }

    @Test
    void bothPathsConsultTheLimitOnEveryRead() throws Exception {
        // A filter may raise maxBodySize mid-request; the limit has to be read
        // per read, not captured once. Both paths hold the same LongSupplier
        // here, which is the shape RequestBody and readBody both use.
        long[] limit = {1};

        var engine = new LimitedInputStream(
            new ByteArrayInputStream(latin("ab")), () -> limit[0]);
        var helper = new LimitedInputStream(
            new ByteArrayInputStream(latin("ab")), () -> limit[0]);

        assertThrows(BodyTooLargeException.class, engine::readAllBytes);
        assertTrue(engine.limitExceeded());
        assertThrows(BodyTooLargeException.class, helper::readAllBytes);
        assertTrue(helper.limitExceeded());

        limit[0] = 10;   // raised mid-request
        var raised = new LimitedInputStream(
            new ByteArrayInputStream(latin("ab")), () -> limit[0]);
        assertEquals(2, raised.readAllBytes().length,
            "a limit raised before the read must take effect immediately");
        assertFalse(raised.limitExceeded());
    }
}
