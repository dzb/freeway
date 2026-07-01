package com.jujin.freeway.http.engine.ws;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.*;

class WebSocketFrameTest {

    @Test
    void maskedFrameReportsMasked() throws Exception {
        // masked text frame "hi" with masking key [1,2,3,4]
        byte[] raw = {(byte) 0x81, (byte) 0x82, 1, 2, 3, 4,
                (byte) ('h' ^ 1), (byte) ('i' ^ 2)};
        var frame = WebSocketFrame.read(new ByteArrayInputStream(raw));
        assertTrue(frame.isMasked(), "Masked client frame should report isMasked=true");
        assertEquals("hi", frame.payloadAsString());
    }

    @Test
    void unmaskedFrameReportsNotMasked() throws Exception {
        // unmasked text frame "hi"
        byte[] raw = {(byte) 0x81, 0x02, 'h', 'i'};
        var frame = WebSocketFrame.read(new ByteArrayInputStream(raw));
        assertFalse(frame.isMasked(), "Unmasked frame should report isMasked=false");
    }
}
