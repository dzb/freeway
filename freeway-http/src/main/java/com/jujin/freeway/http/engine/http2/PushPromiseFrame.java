package com.jujin.freeway.http.engine.http2;

import java.io.IOException;
import java.io.OutputStream;

final class PushPromiseFrame extends BaseFrame {

    private final byte[] body;

    private PushPromiseFrame(FrameHeader header, byte[] body) {
        super(header);
        this.body = body;
    }

    public static PushPromiseFrame parse(byte[] body, FrameHeader header) {
        return new PushPromiseFrame(header, body == null ? new byte[0] : body);
    }

    /**
     * Writes the header and the payload.
     *
     * <p>The promised stream id and the header block are not modelled beyond
     * their bytes, so keeping them is what lets this frame round-trip: writing
     * the header alone would declare a payload length with nothing behind it,
     * and the next caller would inherit that wire format. The length is
     * recomputed because a decoded frame carries the length it arrived with.
     */
    public void writeTo(OutputStream outputStream) throws IOException {
        new FrameHeader(body.length, FrameType.PUSH_PROMISE, header().flags(), header().streamId())
            .writeTo(outputStream);
        outputStream.write(body);
    }
}
