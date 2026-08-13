package com.jujin.freeway.http.engine.http2;

import java.io.IOException;
import java.io.OutputStream;


public final class PingFrame extends BaseFrame {
    public final byte[] body;

    public PingFrame(FrameHeader header, byte[] body) { super(header); this.body = body; }

    public PingFrame() {
        super(new FrameHeader(8, FrameType.PING, FrameFlag.NONE, 0));
        body = new byte[8];
    }

    public PingFrame(PingFrame ack) {
        super(new FrameHeader(8, FrameType.PING, FrameFlag.FlagSet.of(FrameFlag.ACK), 0));
        body = ack.body;
    }

    public static PingFrame parse(byte[] body, FrameHeader header) throws IOException {
        if (header.streamId() != 0) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        if (body.length != 8) throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);
        return new PingFrame(header, body);
    }

    public void writeTo(OutputStream outputStream) throws IOException {
        header().writeTo(outputStream);
        outputStream.write(body);
    }
}
