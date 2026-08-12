package com.jujin.freeway.http.engine.http2.frame;

import com.jujin.freeway.http.engine.http2.util.Http2ErrorCode;
import com.jujin.freeway.http.engine.http2.util.Http2Exception;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HeadersFrameTest {

    @Test
    void paddedFrameWithEmptyPayloadIsProtocolError() {
        // PADDED flag with no payload at all (no pad-length byte) must be
        // rejected as a protocol error, not crash with an index underflow.
        var header = new FrameHeader(0, FrameType.HEADERS,
                FrameFlag.FlagSet.of(FrameFlag.PADDED), 1);
        Http2Exception ex = assertThrows(Http2Exception.class,
            () -> HeadersFrame.parse(new byte[0], header));
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, ex.errorCode());
    }

    @Test
    void paddedFrameSkipsPadLengthByte() throws Exception {
        // payload: [padLen=2, 'A', 'B', pad0, pad1]
        byte[] payload = {2, 'A', 'B', 0, 0};
        var header = new FrameHeader(5, FrameType.HEADERS,
                FrameFlag.FlagSet.of(FrameFlag.PADDED, FrameFlag.END_HEADERS), 1);
        var frame = HeadersFrame.parse(payload, header);
        assertArrayEquals(new byte[]{'A', 'B'}, frame.headerBlock(),
                "Pad length byte and trailing padding must not be in the header block");
    }

    @Test
    void priorityFieldMustBeComplete() {
        var header = new FrameHeader(4, FrameType.HEADERS,
                FrameFlag.FlagSet.of(FrameFlag.PRIORITY), 1);
        Http2Exception ex = assertThrows(Http2Exception.class,
            () -> HeadersFrame.parse(new byte[4], header));
        assertEquals(Http2ErrorCode.FRAME_SIZE_ERROR, ex.errorCode());
    }
}
