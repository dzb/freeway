package com.jujin.freeway.http.engine.http20.frame;
import com.jujin.freeway.http.engine.http20.util.Http2ErrorCode;
import com.jujin.freeway.http.engine.http20.util.Http2Exception;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

public final class FrameSerializer {
    /** HTTP/2 max frame size (16KB default, RFC 7540 Section 4.2). */
    private static final int MAX_FRAME_SIZE = 16384;

    private FrameSerializer() {}

    /**
     * Reads a 9-byte frame header from the input stream, validates the frame
     * size against the max allowed, reads the frame body, and dispatches to
     * the appropriate frame-type parser.
     */
    public static BaseFrame deserialize(InputStream inputStream) throws IOException {
        byte[] headerBuffer = new byte[9];
        readFully(inputStream, headerBuffer);
        var header = FrameHeader.parse(headerBuffer);
        if (header.length() > MAX_FRAME_SIZE)
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);
        byte[] body = new byte[header.length()];
        readFully(inputStream, body);
        return switch (header.type()) {
            case HEADERS -> HeadersFrame.parse(body, header);
            case CONTINUATION -> ContinuationFrame.parse(body, header);
            case DATA -> DataFrame.parse(body, header);
            case GOAWAY -> GoawayFrame.parse(body, header);
            case PING -> PingFrame.parse(body, header);
            case PRIORITY -> PriorityFrame.parse(body, header);
            case PUSH_PROMISE -> PushPromiseFrame.parse(body, header);
            case RST_STREAM -> ResetStreamFrame.parse(body, header);
            case SETTINGS -> SettingsFrame.parse(body, header);
            case WINDOW_UPDATE -> WindowUpdateFrame.parse(body, header);
            default -> NotImplementedFrame.parse(body, header);
        };
    }

    /**
     * Reads exactly {@code buffer.length} bytes from the input stream,
     * blocking until complete. Throws {@link EOFException} if the stream
     * ends before all bytes are received.
     */
    public static void readFully(InputStream inputStream, byte[] buffer) throws IOException {
        if (buffer.length == 0) return;
        int bytesRead;
        int offset = 0;
        int length = buffer.length;
        while (offset < length) {
            bytesRead = inputStream.read(buffer, offset, length - offset);
            if (bytesRead < 0) {
                if (offset == 0) throw new EOFException("end of stream detected");
                throw new IOException("failed to read the full buffer: read " + offset + " of " + length + " bytes");
            }
            offset += bytesRead;
        }
    }
}
