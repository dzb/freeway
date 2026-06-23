package com.jujin.freeway.http.engine.http20.frame;

import java.io.IOException;
import java.io.OutputStream;

public final class PushPromiseFrame extends BaseFrame {
    public PushPromiseFrame(FrameHeader header) { super(header); }
    public static PushPromiseFrame parse(byte[] body, FrameHeader header) { return new PushPromiseFrame(header); }
    public void writeTo(OutputStream outputStream) throws IOException { header().writeTo(outputStream); }
}
