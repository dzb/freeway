package com.jujin.freeway.http.engine.http2.frame;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

import com.jujin.freeway.http.engine.http2.util.BinUtils;
import com.jujin.freeway.http.engine.http2.util.Http2ErrorCode;
import com.jujin.freeway.http.engine.http2.util.Http2Exception;

public final class HeadersFrame extends BaseFrame {
    private int padLength;
    private long dependentStreamId;
    private byte[] headerBlock;

    public HeadersFrame() { this(new FrameHeader(0, FrameType.HEADERS, FrameFlag.NONE, 0)); }
    public HeadersFrame(FrameHeader header) { super(header); }

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
            frame.padLength = BinUtils.readInt(payload, pos, 1);
            if (frame.padLength >= header.length()) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
            pos++;
        }
        int end = header.length() - frame.padLength;
        if (header.flags().contains(FrameFlag.PRIORITY)) {
            if (end - pos < 5)
                throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR,
                    "HEADERS PRIORITY field is truncated");
            frame.dependentStreamId = BinUtils.readInt(payload, pos, 4) & 0x7FFFFFFFL;
            if (frame.dependentStreamId == header.streamId()) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
            pos += 5;
        }
        if (end < pos) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        frame.headerBlock = Arrays.copyOfRange(payload, pos, end);
        return frame;
    }

    public void writeTo(OutputStream outputStream) throws IOException {
        byte[] buffer = headerBlock;
        FrameHeader.writeTo(outputStream, buffer.length, FrameType.HEADERS,
            FrameFlag.FlagSet.of(FrameFlag.END_HEADERS), header().streamId());
        outputStream.write(buffer);
        outputStream.flush();
    }
}
