package com.jujin.freeway.http.engine.http2;

import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DataFrameTest {

    @Test
    void paddedFrameWithOnlyPaddingIsValid() throws Exception {
        // padLen=3, total body=4 (padLen byte + 3 padding bytes, no data)
        byte[] body = {3, 0, 0, 0};
        var header = new FrameHeader(4, FrameType.DATA,
                FrameFlag.FlagSet.of(FrameFlag.PADDED), 1);
        var frame = DataFrame.parse(body, header);
        byte[] result = ((DataFrame) frame).body;
        assertEquals(0, result.length, "Empty data + padding should produce zero-length body");
        assertEquals(4, ((DataFrame) frame).flowLength(),
            "flow control must count the pad-length byte and padding");
    }

    @Test
    void paddedFrameSkipsPadLengthByte() throws Exception {
        // body: [padLen=5, 'A', 'B', pad0, pad1, pad2, pad3, pad4]
        byte[] body = {5, 'A', 'B', 0, 0, 0, 0, 0};
        var header = new FrameHeader(8, FrameType.DATA,
                FrameFlag.FlagSet.of(FrameFlag.PADDED), 1);
        var frame = DataFrame.parse(body, header);
        byte[] result = ((DataFrame) frame).body;
        assertArrayEquals(new byte[]{'A', 'B'}, result,
                "Pad length byte (index 0) must not be in body");
        assertEquals(8, ((DataFrame) frame).flowLength(),
            "flow control must count the pad-length byte and padding");
    }

    @Test
    void paddedFrameWithEmptyBodyIsProtocolError() {
        // PADDED flag with no payload at all (no pad-length byte) must be
        // rejected as a protocol error, not crash with an index underflow.
        var header = new FrameHeader(0, FrameType.DATA,
                FrameFlag.FlagSet.of(FrameFlag.PADDED), 1);
        Http2Exception ex = assertThrows(Http2Exception.class,
            () -> DataFrame.parse(new byte[0], header));
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, ex.errorCode());
    }
}
