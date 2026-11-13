package com.jujin.freeway.http.engine.http2;

import java.io.IOException;
import java.io.OutputStream;


final class ResetStreamFrame implements Http2Frame {
    private final FrameHeader header;

    @Override
    public FrameHeader header() { return header; }

    public final Http2ErrorCode errorCode;

    public ResetStreamFrame(Http2ErrorCode errorCode, int streamId) {
        this.header = new FrameHeader(4, FrameType.RST_STREAM, FrameFlag.NONE, streamId);
        this.errorCode = errorCode;
    }

    private ResetStreamFrame(FrameHeader header, Http2ErrorCode errorCode) {
        this.header = header;
        this.errorCode = errorCode;
    }

    public static ResetStreamFrame parse(byte[] body, FrameHeader header) throws IOException {
        if (body.length != 4) throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);
        return new ResetStreamFrame(header, Http2ErrorCode.fromValue(Bytes.readInt(body, 0)));
    }

    public void writeTo(OutputStream outputStream) throws IOException {
        header().writeTo(outputStream);
        Bytes.writeInt(outputStream, errorCode.value);
        outputStream.flush();
    }
}
