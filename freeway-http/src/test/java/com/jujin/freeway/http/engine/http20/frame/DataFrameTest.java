package com.jujin.freeway.http.engine.http20.frame;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DataFrameTest {

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
    }
}
