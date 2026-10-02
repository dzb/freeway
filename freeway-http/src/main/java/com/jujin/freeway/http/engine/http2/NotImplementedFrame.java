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
     * Writes the header and the payload.
     *
     * <p>The payload is the point of this class — it holds the bytes of a frame
     * type this engine does not interpret, and the length in the header
     * describes them, so dropping it would write a frame whose declared length
     * disagrees with the bytes after it.
     *
     * <p>The type and flags do <em>not</em> round-trip, and cannot: by the time
     * anything gets here {@link FrameType#fromValue} has already folded every
     * unknown type into {@code NOT_IMPLEMENTED}, and {@link FrameFlag#parse} has
     * masked the flags to the defined bits. So a frame of type {@code 0xFF} is
     * re-encoded as {@code 0x0A}, with masked flags. Only the payload is
     * preserved verbatim.
     */
    public void writeTo(OutputStream outputStream) throws IOException {
        new FrameHeader(body.length, header().type(), header().flags(), header().streamId())
            .writeTo(outputStream);
        outputStream.write(body);
    }
}
