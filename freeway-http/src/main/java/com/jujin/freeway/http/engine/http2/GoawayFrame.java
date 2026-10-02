package com.jujin.freeway.http.engine.http2;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;


final class GoawayFrame extends BaseFrame {
    public final Http2ErrorCode errorCode;
    public final int lastSeenStream;
    /** RFC 9113 §6.8 optional diagnostic data; empty when the peer sent none. */
    private final byte[] debugData;

    public GoawayFrame(Http2ErrorCode errorCode, int lastSeenStream) {
        super(new FrameHeader(8, FrameType.GOAWAY, FrameFlag.NONE, 0));
        this.errorCode = errorCode;
        this.lastSeenStream = lastSeenStream;
        this.debugData = new byte[0];
    }

    private GoawayFrame(FrameHeader header, Http2ErrorCode errorCode, int lastSeenStream, byte[] debugData) {
        super(header); this.errorCode = errorCode; this.lastSeenStream = lastSeenStream;
        this.debugData = debugData;
    }

    public static GoawayFrame parse(byte[] body, FrameHeader header) throws IOException {
        if (header.streamId() != 0) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        if (body.length < 8) throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);
        // RFC 9113 §6.8: the high bit of the last-stream-id is reserved and a
        // receiver must ignore it. Masked here, as FrameHeader.parse and the
        // PRIORITY field both already do — and unlike those, this one feeds a
        // public field that writeTo now puts back on the wire, so an unmasked
        // value would re-emit a protocol violation the parse just accepted.
        return new GoawayFrame(header, Http2ErrorCode.fromValue(Bytes.readInt(body, 4, 4)),
            Bytes.readInt(body, 0) & 0x7FFFFFFF, Arrays.copyOfRange(body, 8, body.length));
    }

    /**
     * Writes header, last-stream-id, error code and any debug data.
     *
     * <p>{@link #parse} accepts the debug data RFC 9113 §6.8 allows after the
     * first eight bytes; dropping it here would write eight bytes under the
     * length the peer declared. The length is recomputed for the same reason a
     * decoded frame's header length cannot be reused.
     */
    public void writeTo(OutputStream outputStream) throws IOException {
        new FrameHeader(8 + debugData.length, FrameType.GOAWAY, header().flags(), 0)
            .writeTo(outputStream);
        Bytes.writeInt(outputStream, lastSeenStream);
        Bytes.writeInt(outputStream, errorCode.value);
        if (debugData.length > 0) {
            outputStream.write(debugData);
        }
    }
}
