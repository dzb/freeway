package com.jujin.freeway.http.engine.http2;

import java.io.IOException;
import java.io.OutputStream;

final class NotImplementedFrame extends BaseFrame {

    private final byte[] body;

    private NotImplementedFrame(FrameHeader header, byte[] body) {
        super(header);
        this.body = body;
    }

    public static NotImplementedFrame parse(byte[] body, FrameHeader header) {
        return new NotImplementedFrame(header, body == null ? new byte[0] : body);
    }

    /**
     * Writes the header and the payload — the bytes of a frame type this
     * engine does not interpret, which is exactly why they must be preserved
     * rather than dropped: the length in the header describes them.
     */
    public void writeTo(OutputStream outputStream) throws IOException {
        new FrameHeader(body.length, header().type(), header().flags(), header().streamId())
            .writeTo(outputStream);
        outputStream.write(body);
    }
}
