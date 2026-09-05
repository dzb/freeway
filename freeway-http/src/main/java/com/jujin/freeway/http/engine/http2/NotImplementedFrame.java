package com.jujin.freeway.http.engine.http2;

import java.io.IOException;
import java.io.OutputStream;

final class NotImplementedFrame extends BaseFrame {
    public NotImplementedFrame(FrameHeader header, byte[] body) { super(header); }
    public static NotImplementedFrame parse(byte[] body, FrameHeader header) { return new NotImplementedFrame(header, body); }
    public void writeTo(OutputStream outputStream) throws IOException { header().writeTo(outputStream); }
}
