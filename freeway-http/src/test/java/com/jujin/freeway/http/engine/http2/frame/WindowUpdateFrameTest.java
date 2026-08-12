package com.jujin.freeway.http.engine.http2.frame;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowUpdateFrameTest {

    @Test
    void incrementMasksReservedBit() throws Exception {
        byte[] body = {(byte) 0x80, 0, 0, 1};
        var header = new FrameHeader(4, FrameType.WINDOW_UPDATE,
                FrameFlag.NONE, 0);
        var frame = WindowUpdateFrame.parse(body, header);
        assertEquals(1, frame.increment(),
                "Reserved bit should be masked, giving increment=1 not negative");
    }

    @Test
    void zeroIncrementParsesButIsRejectedUpstream() throws Exception {
        byte[] body = {0, 0, 0, 0};
        var header = new FrameHeader(4, FrameType.WINDOW_UPDATE,
                FrameFlag.NONE, 0);
        var frame = WindowUpdateFrame.parse(body, header);
        assertEquals(0, frame.increment(),
            "Zero must parse at the frame layer; its error scope is decided "
                + "by the connection/stream validator (RFC 7540 §6.9)");
    }
}
