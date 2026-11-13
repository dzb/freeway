package com.jujin.freeway.http.engine.http2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * A parsed or outbound HTTP/2 frame. The hierarchy is sealed and package
 * private: frames are a closed protocol vocabulary, not an extension point.
 * Each frame owns its {@link FrameHeader} by composition; protocol dispatch
 * uses a pattern switch over this interface instead of casts.
 */
sealed interface Http2Frame permits HeadersFrame, ContinuationFrame, DataFrame,
        GoawayFrame, PingFrame, PriorityFrame, PushPromiseFrame, ResetStreamFrame,
        SettingsFrame, WindowUpdateFrame, NotImplementedFrame {

    /** The frame's wire header. */
    FrameHeader header();

    /** Writes the header and payload. */
    void writeTo(OutputStream outputStream) throws IOException;

    /** Encodes the frame as a standalone byte array. */
    default byte[] encode() {
        var buffer = new ByteArrayOutputStream();
        try {
            writeTo(buffer);
        } catch (IOException ignored) {
            // ByteArrayOutputStream never throws.
        }
        return buffer.toByteArray();
    }
}
