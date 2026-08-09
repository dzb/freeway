package com.jujin.freeway.http.engine.http2.frame;
import com.jujin.freeway.http.engine.http2.util.Http2ErrorCode;
import com.jujin.freeway.http.engine.http2.util.Http2Exception;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

public final class FrameSerializer {
    /** HTTP/2 max frame size (16KB default, RFC 7540 Section 4.2). */
    private static final int DEFAULT_MAX_FRAME_SIZE = 16384;

    private FrameSerializer() {}

    public static BaseFrame deserialize(InputStream inputStream) throws IOException {
        return deserialize(inputStream, DEFAULT_MAX_FRAME_SIZE);
    }

    public static BaseFrame deserialize(InputStream inputStream, int maxFrameSize) throws IOException {
        return deserialize(inputStream, maxFrameSize, new byte[9]);
    }

    /**
     * Deserializes one frame, reusing the caller's 9-byte header buffer so the
     * per-frame read loop avoids one allocation. The buffer is owned by the
     * connection's single reader thread — never share it across threads.
     */
    public static BaseFrame deserialize(InputStream inputStream, int maxFrameSize,
                                        byte[] headerBuffer) throws IOException {
        readFully(inputStream, headerBuffer);
        var header = FrameHeader.parse(headerBuffer);
        if (header.length() > maxFrameSize)
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
