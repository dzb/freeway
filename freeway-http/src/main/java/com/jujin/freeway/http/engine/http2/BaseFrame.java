package com.jujin.freeway.http.engine.http2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public abstract class BaseFrame {
    private FrameHeader header;

    public BaseFrame(FrameHeader header) { this.header = header; }
    public FrameHeader header() { return header; }
    public abstract void writeTo(OutputStream outputStream) throws IOException;

    public byte[] encode() {
        var buffer = new ByteArrayOutputStream();
        try { writeTo(buffer); } catch (IOException ignored) {}
        return buffer.toByteArray();
    }
}
