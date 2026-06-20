package com.jujin.freeway.http.engine.http20.frame;
import com.jujin.freeway.http.engine.http20.util.BinUtils;
import com.jujin.freeway.http.engine.http20.util.Http2ErrorCode;
import com.jujin.freeway.http.engine.http20.util.Http2Exception;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

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
            frame.padLength = BinUtils.readInt(payload, pos, 1);
            if (frame.padLength >= header.length()) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
            pos++;
        }
        if (header.flags().contains(FrameFlag.PRIORITY)) {
            frame.dependentStreamId = BinUtils.readInt(payload, pos, 4) & 0x7FFFFFFFL;
            if (frame.dependentStreamId == header.streamId()) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
            pos += 5;
        }
        frame.headerBlock = Arrays.copyOfRange(payload, pos, header.length() - frame.padLength);
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
