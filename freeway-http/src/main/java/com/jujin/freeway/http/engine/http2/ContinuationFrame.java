package com.jujin.freeway.http.engine.http2;

import java.io.IOException;
import java.io.OutputStream;

/** A header block split across frames (RFC 9113 §6.10). Public because
 *  {@code hpack.HPackContext} owns header framing and cannot see
 *  package-private siblings — an intra-engine contract, not API. */
public final class ContinuationFrame extends BaseFrame {
    private final byte[] headerBlock;

    public ContinuationFrame(FrameHeader header, byte[] headerBlock) {
        super(header);
        this.headerBlock = headerBlock;
    }

    public static ContinuationFrame parse(byte[] body, FrameHeader header) {
        return new ContinuationFrame(header, body);
    }

    public byte[] headerBlock() {
        return headerBlock;
    }

    public void writeTo(OutputStream outputStream) throws IOException {
        header().writeTo(outputStream);
        outputStream.write(headerBlock);
    }
}
