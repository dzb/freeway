package com.jujin.freeway.http.engine.http20.frame;

import com.jujin.freeway.http.engine.http20.util.Http2Exception;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

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
    void zeroIncrementThrows() {
        byte[] body = {0, 0, 0, 0};
        var header = new FrameHeader(4, FrameType.WINDOW_UPDATE,
                FrameFlag.NONE, 0);
        assertThrows(Http2Exception.class,
                () -> WindowUpdateFrame.parse(body, header));
    }
}
