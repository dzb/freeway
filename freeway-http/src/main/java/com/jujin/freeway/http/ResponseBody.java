package com.jujin.freeway.http;

import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The body half of a response, independent of how a transport frames it.
 *
 * <p>A plan carries the body once; the transport decides how the bytes reach
 * its wire. This replaces the four parallel body methods on
 * {@link HttpTransport} (bytes / stream / path / channel) whose defaults had
 * grown a second copy of the gzip, length and HEAD policy.</p>
 *
 * <p>{@link #length()} is the source length, or {@code -1} when unknown. For a
 * {@link Stream} the length is a declaration from the producer; for a
 * {@link File} or {@link Channel} it limits the range (negative means "to the
 * end").</p>
 */
public sealed interface ResponseBody
        permits ResponseBody.Empty, ResponseBody.Bytes,
                ResponseBody.Stream, ResponseBody.File, ResponseBody.Channel {

    /** Source length in bytes, or {@code -1} when unknown. */
    long length();

    /** No body bytes at all. */
    record Empty() implements ResponseBody {
        public static final Empty INSTANCE = new Empty();

        @Override
        public long length() {
            return 0;
        }
    }

    /** A fully materialized body. */
    record Bytes(byte[] data) implements ResponseBody {
        public Bytes {
            Objects.requireNonNull(data, "data");
        }

        @Override
        public long length() {
            return data.length;
        }
    }

    /** A producer stream; {@code length} may be {@code -1}. */
    record Stream(InputStream stream, long length) implements ResponseBody {
        public Stream {
            Objects.requireNonNull(stream, "stream");
        }
    }

    /** A file path, opened by the transport with no-follow semantics. */
    record File(Path path, long offset, long length) implements ResponseBody {
        public File {
            Objects.requireNonNull(path, "path");
            if (offset < 0) {
                throw new IllegalArgumentException("offset must be >= 0: " + offset);
            }
        }
    }

    /** An already-open channel, owned by the transport from here on. */
    record Channel(FileChannel channel, long offset, long length)
            implements ResponseBody {
        public Channel {
            Objects.requireNonNull(channel, "channel");
            if (offset < 0) {
                throw new IllegalArgumentException("offset must be >= 0: " + offset);
            }
        }
    }
}
