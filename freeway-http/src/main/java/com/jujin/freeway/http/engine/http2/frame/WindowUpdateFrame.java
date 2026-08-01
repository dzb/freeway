package com.jujin.freeway.http.engine.http2.frame;
import com.jujin.freeway.http.engine.http2.util.BinUtils;
import com.jujin.freeway.http.engine.http2.util.Http2ErrorCode;
import com.jujin.freeway.http.engine.http2.util.Http2Exception;

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
        if (frame.windowSizeIncrement == 0) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        return frame;
    }

    public int increment() { return windowSizeIncrement; }

    public void writeTo(OutputStream outputStream) throws IOException {
        header().writeTo(outputStream);
        BinUtils.writeInt(outputStream, windowSizeIncrement);
    }

    public byte[] encode() {
        byte[] buffer = new byte[4];
        BinUtils.writeInt(buffer, 0, windowSizeIncrement);
        return buffer;
    }
}
