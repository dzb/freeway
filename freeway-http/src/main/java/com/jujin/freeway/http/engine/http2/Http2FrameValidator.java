package com.jujin.freeway.http.engine.http2;

import com.jujin.freeway.http.engine.http2.frame.DataFrame;
import com.jujin.freeway.http.engine.http2.frame.FrameSerializer;
import com.jujin.freeway.http.engine.http2.util.Http2ErrorCode;
import com.jujin.freeway.http.engine.http2.util.Http2Exception;

/**
 * Central frame/state validation for the HTTP/2 connection and its streams.
 *
 * <p>Responsibility boundaries: frame-length enforcement lives in
 * {@link FrameSerializer} (deserialization caps payloads at the advertised
 * {@code SETTINGS_MAX_FRAME_SIZE}); padding accounting lives in
 * {@link DataFrame#flowLength()}. This class owns the remaining protocol
 * checks shared by the connection frame loop and the stream dispatcher.</p>
 */
final class Http2FrameValidator {

    private Http2FrameValidator() {}

    /** RFC 7540 §5.1.1: client-initiated streams use odd stream ids; an even
     *  stream id on the wire is a connection error. */
    static void requireClientStreamId(int streamId) throws Http2Exception {
        if (streamId != 0 && streamId % 2 == 0) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        }
    }

    /** RFC 7540 §4.3: a header block may be interrupted only by CONTINUATION
     *  frames; any other frame mid-block is a connection error. */
    static void requireNotInHeaderBlock(boolean inHeaders)
            throws Http2Exception {
        if (inHeaders) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        }
    }

    /** RFC 7540 §6.9.1: flow-control windows are 31-bit; an increment that
     *  pushes a window past 2^31-1 is a FLOW_CONTROL_ERROR. */
    static boolean sendWindowOverflow(long newValue) {
        return newValue > Integer.MAX_VALUE;
    }

    /** RFC 7540 §6.9: a zero window increment is a PROTOCOL_ERROR — a
     *  connection error for the connection window, a stream error for a
     *  stream window. */
    static void requirePositiveWindowIncrement(int increment)
            throws Http2Exception {
        if (increment == 0) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        }
    }
}
