package com.jujin.freeway.http.engine.http2.hpack;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HPackContextTest {

    @Test
    void readIntThrowsOnTruncatedBlock() {
        // 0xFF at prefix=1 triggers multi-byte read but only 1 byte available
        assertThrows(ArrayIndexOutOfBoundsException.class,
                () -> HPackContext.readInt(new byte[]{(byte) 0xFF}, 0, 1),
                "Truncated HPACK block should throw, not silently return");
    }

    @Test
    void encodeStringHandlesLength128() {
        byte[] value = new byte[128];
        byte[] encoded = HPackContext.encodeString(value);
        assertEquals((byte) 0x7F, encoded[0],
                "Prefix for length >=127 must be 0x7F per HPACK spec");
        assertEquals(0x01, encoded[1] & 0xFF,
                "Continuation for 128 encodes remainder 1");
        assertEquals(2 + 128, encoded.length,
                "2-byte header + 128-byte payload");
    }

    @Test
    void encodeStringHandlesLength127() {
        byte[] value = new byte[127];
        byte[] encoded = HPackContext.encodeString(value);
        // 127 >= 127 (prefixMask) → long encoding: 2-byte header + data
        assertEquals((byte) 0x7F, encoded[0], "Prefix must be 0x7F");
        assertEquals(2 + 127, encoded.length,
                "127 needs 2-byte header (0x7F + 0x00) + 127 data = 129");
    }
}
