package com.jujin.freeway.http.engine.http2;

import java.io.IOException;
import java.io.OutputStream;


final class ResetStreamFrame extends BaseFrame {
    public final Http2ErrorCode errorCode;

    public ResetStreamFrame(Http2ErrorCode errorCode, int streamId) {
        super(new FrameHeader(4, FrameType.RST_STREAM, FrameFlag.NONE, streamId));
        this.errorCode = errorCode;
    }

    private ResetStreamFrame(FrameHeader header, Http2ErrorCode errorCode) {
        super(header);
        this.errorCode = errorCode;
    }

    public static ResetStreamFrame parse(byte[] body, FrameHeader header) throws IOException {
        if (body.length != 4) throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);
        return new ResetStreamFrame(header, Http2ErrorCode.fromValue(BinUtils.readInt(body, 0)));
    }

    public void writeTo(OutputStream outputStream) throws IOException {
        header().writeTo(outputStream);
        BinUtils.writeInt(outputStream, errorCode.value);
        outputStream.flush();
    }
}
