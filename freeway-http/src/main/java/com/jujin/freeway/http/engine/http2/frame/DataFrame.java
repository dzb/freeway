package com.jujin.freeway.http.engine.http2.frame;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

import com.jujin.freeway.http.engine.http2.util.Http2ErrorCode;
import com.jujin.freeway.http.engine.http2.util.Http2Exception;

public final class DataFrame extends BaseFrame {
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

    /** Padding bytes (excluding the pad-length byte itself). */
    public int padLength() {
        return padLength;
    }

    /** Flow-controlled length: data + pad-length byte + padding (RFC 7540 §6.9.1). */
    public int flowLength() {
        return body.length + padLength + (header().flags().contains(FrameFlag.PADDED) ? 1 : 0);
    }

    public void writeTo(OutputStream outputStream) throws IOException { outputStream.write(body); }
}
