package com.jujin.freeway.http.engine.http2;

import java.io.IOException;
import java.io.OutputStream;


public final class PriorityFrame extends BaseFrame {
    public int streamDep;
    public int weight;
    public boolean excl;

    public PriorityFrame(FrameHeader header) {
        super(header);
    }

    public static PriorityFrame parse(byte[] body, FrameHeader header) throws IOException {
        if (body.length != 5) throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);
        var frame = new PriorityFrame(header);
        int temp = BinUtils.readInt(body, 0, 4);
        frame.excl = (temp & 0x80000000) != 0;
        frame.streamDep = temp & 0x7FFFFFFF;
        if (frame.streamDep == header.streamId()) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        frame.weight = (body[4] & 0xFF) + 1;
        return frame;
    }

    public void writeTo(OutputStream outputStream) throws IOException {
        header().writeTo(outputStream);
        byte[] body = new byte[5];
        int dep = streamDep | (excl ? 0x80000000 : 0);
        BinUtils.writeInt(body, 0, dep, 4);
        body[4] = (byte) (weight - 1);
        outputStream.write(body);
    }
}
