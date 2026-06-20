package com.jujin.freeway.http.engine.http20.frame;
import com.jujin.freeway.http.engine.http20.util.BinUtils;
import com.jujin.freeway.http.engine.http20.util.Http2ErrorCode;
import com.jujin.freeway.http.engine.http20.util.Http2Exception;

import java.io.IOException;
import java.io.OutputStream;

public final class GoawayFrame extends BaseFrame {
    public final Http2ErrorCode errorCode;
    public final int lastSeenStream;

    public GoawayFrame(Http2ErrorCode errorCode, int lastSeenStream) {
        super(new FrameHeader(8, FrameType.GOAWAY, FrameFlag.NONE, 0));
        this.errorCode = errorCode;
        this.lastSeenStream = lastSeenStream;
    }

    private GoawayFrame(FrameHeader header, Http2ErrorCode errorCode, int lastSeenStream) {
        super(header); this.errorCode = errorCode; this.lastSeenStream = lastSeenStream;
    }

    public static GoawayFrame parse(byte[] body, FrameHeader header) throws IOException {
        if (header.streamId() != 0) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        if (body.length < 8) throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);
        return new GoawayFrame(header, Http2ErrorCode.fromValue(BinUtils.readInt(body, 4, 4)), BinUtils.readInt(body, 0));
    }

    public void writeTo(OutputStream outputStream) throws IOException {
        header().writeTo(outputStream);
        BinUtils.writeInt(outputStream, lastSeenStream);
        BinUtils.writeInt(outputStream, errorCode.value);
    }
}
