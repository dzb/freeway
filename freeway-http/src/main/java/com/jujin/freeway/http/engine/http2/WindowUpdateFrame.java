package com.jujin.freeway.http.engine.http2;

import java.io.IOException;
import java.io.OutputStream;


public final class WindowUpdateFrame extends BaseFrame {
    private int windowSizeIncrement;

    public WindowUpdateFrame(FrameHeader header) {
        super(header);
    }

    public WindowUpdateFrame(int streamId, int windowSizeIncrement) {
        super(new FrameHeader(4, FrameType.WINDOW_UPDATE, FrameFlag.NONE, streamId));
        this.windowSizeIncrement = windowSizeIncrement;
    }

    public static WindowUpdateFrame parse(byte[] body, FrameHeader header) throws IOException {
        if (body.length != 4) throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);
        var frame = new WindowUpdateFrame(header);
        frame.windowSizeIncrement = BinUtils.readInt(body, 0) & 0x7FFFFFFF;
        // A zero increment is syntactically valid here; its error scope is
        // semantic and enforced by Http2FrameValidator at the connection
        // (GOAWAY) or stream (RST_STREAM) layer per RFC 7540 §6.9.
        return frame;
    }

    public int increment() { return windowSizeIncrement; }

    public void writeTo(OutputStream outputStream) throws IOException {
        header().writeTo(outputStream);
        BinUtils.writeInt(outputStream, windowSizeIncrement);
    }
}
