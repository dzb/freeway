package com.jujin.freeway.http.engine.http2;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;


final class HeadersFrame extends BaseFrame {
    private int padLength;
    private long dependentStreamId;
    /** The PRIORITY dependency's E bit (RFC 9113 §6.10): an exclusive dependency. */
    private boolean exclusive;
    private byte weight;
    private byte[] headerBlock;

    /** For {@link #parse}, which fills the block in from the payload. */
    private HeadersFrame(FrameHeader header) { super(header); }

    public byte[] headerBlock() { return headerBlock; }

    public static HeadersFrame parse(byte[] payload, FrameHeader header) throws IOException {
        if (payload == null || header.length() != payload.length)
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);
        var frame = new HeadersFrame(header);
        int pos = 0;
        if (header.flags().contains(FrameFlag.PADDED)) {
            // A PADDED frame must carry at least the pad-length byte — an
            // empty payload with the PADDED flag is a protocol error, not an
            // index-underflow crash in readInt.
            if (payload.length < 1)
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR,
                    "PADDED frame with no pad-length byte");
            frame.padLength = Bytes.readInt(payload, pos, 1);
            if (frame.padLength >= header.length()) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
            pos++;
        }
        int end = header.length() - frame.padLength;
        if (header.flags().contains(FrameFlag.PRIORITY)) {
            if (end - pos < 5)
                throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR,
                    "HEADERS PRIORITY field is truncated");
            int rawDependency = Bytes.readInt(payload, pos, 4);
            frame.exclusive = (rawDependency & 0x80000000) != 0;
            frame.dependentStreamId = rawDependency & 0x7FFFFFFFL;
            if (frame.dependentStreamId == header.streamId()) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
            frame.weight = payload[pos + 4];
            pos += 5;
        }
        if (end < pos) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        frame.headerBlock = Arrays.copyOfRange(payload, pos, end);
        return frame;
    }

    /**
     * Serializes this frame with its own parsed flags — the uniform shape of
     * every frame class here. The <em>response</em> path does not use it: a
     * server-side header block needs END_STREAM on the HEADERS frame and
     * END_HEADERS withheld when the block continues in CONTINUATION frames
     * (RFC 9113 §6.10), which
     * {@code hpack.HPackContext.encodeResponseHeaders} owns.
     *
     * <p>Writes the pad-length byte, the PRIORITY field and the padding, all
     * of which {@link #parse} consumes and this previously dropped — a future
     * caller would have emitted a HEADERS frame that no peer could decode.
     * The PRIORITY dependency's E bit travels with it for the same reason: the
     * previous implementation wrote the 31-bit id alone, so an exclusive
     * dependency silently became non-exclusive on the wire.
     * The frame length is recomputed because a decoded frame carries the
     * length it arrived with, padding and priority bytes included.
     */
    public void writeTo(OutputStream outputStream) throws IOException {
        boolean padded = header().flags().contains(FrameFlag.PADDED);
        boolean prioritised = header().flags().contains(FrameFlag.PRIORITY);
        int length = headerBlock.length
            + (padded ? 1 + padLength : 0)
            + (prioritised ? 5 : 0);
        new FrameHeader(length, FrameType.HEADERS, header().flags(), header().streamId())
            .writeTo(outputStream);
        if (padded) {
            outputStream.write(padLength);
        }
        if (prioritised) {
            Bytes.writeInt(outputStream,
                (int) dependentStreamId | (exclusive ? 0x80000000 : 0), 4);
            outputStream.write(weight);
        }
        outputStream.write(headerBlock);
        if (padded && padLength > 0) {
            // Zeros, not the bytes that arrived — parse keeps the padding's
            // length only, and RFC 9113 §6.1 lets a receiver ignore padding. The
            // header block is what round-trips.
            outputStream.write(new byte[padLength]);
        }
    }
}
