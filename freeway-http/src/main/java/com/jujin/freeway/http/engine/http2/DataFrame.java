package com.jujin.freeway.http.engine.http2;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;


final class DataFrame extends BaseFrame {
    public final byte[] body;
    private final int padLength;

    public DataFrame(FrameHeader header, byte[] body) {
        super(header);
        this.body = body;
        this.padLength = 0;
    }

    private DataFrame(FrameHeader header, byte[] body, int padLength) {
        super(header);
        this.body = body;
        this.padLength = padLength;
    }

    public static BaseFrame parse(byte[] body, FrameHeader header) throws IOException {
        int index = 0;
        int padLen = 0;
        if (header.flags().contains(FrameFlag.PADDED)) {
            // A PADDED frame must carry at least the pad-length byte — an
            // empty body with the PADDED flag is a protocol error, not an
            // index-underflow crash.
            if (body.length < 1)
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "PADDED frame with no pad-length byte");
            padLen = body[index++] & 0xFF;
            // RFC 7540 §6.1: pad length must be strictly less than the total
            // payload length (which includes the pad-length byte itself).
            if (padLen > body.length - index) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        }
        return new DataFrame(header,
                Arrays.copyOfRange(body, index, body.length - padLen), padLen);
    }

    /** Flow-controlled length: data + pad-length byte + padding (RFC 7540 §6.9.1). */
    public int flowLength() {
        return body.length + padLength + (header().flags().contains(FrameFlag.PADDED) ? 1 : 0);
    }

    /**
     * Writes header, pad-length byte, payload and padding — the whole frame.
     *
     * <p>Previously this emitted only the payload, so any future caller would
     * have written a headerless, padding-less frame to the wire. The length in
     * the header is recomputed rather than reused from {@code header()}, since
     * a decoded frame carries the length it arrived with and a padded one
     * includes bytes this class strips.
     */
    public void writeTo(OutputStream outputStream) throws IOException {
        boolean padded = header().flags().contains(FrameFlag.PADDED);
        int length = body.length + (padded ? 1 + padLength : 0);
        new FrameHeader(length, FrameType.DATA, header().flags(), header().streamId())
            .writeTo(outputStream);
        if (padded) {
            outputStream.write(padLength);
        }
        outputStream.write(body);
        if (padded && padLength > 0) {
            // Re-emitted as zeros, not the bytes that arrived: parse strips the
            // padding and keeps only its length. RFC 9113 §6.1 lets a receiver
            // ignore padding, so the frame is correct — it is the payload that
            // round-trips, not the frame's padding.
            outputStream.write(new byte[padLength]);
        }
    }
}
