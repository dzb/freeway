package com.jujin.freeway.http.engine.http2.hpack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
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

    @Test
    void dynamicTableIsBoundedByMaxSize() throws IOException {
        HPackContext hpack = new HPackContext();
        hpack.decode(incrementalBlock(100, 100, 100));

        // 100 entries of 232 bytes each exceed the 4096-byte table limit;
        // only the newest entries may remain.
        assertNotNull(hpack.get(62), "Newest dynamic table entry must be stored");
        assertNull(hpack.get(62 + 30), "Old entries must be evicted");
    }

    @Test
    void oversizedFieldIsNotAddedToDynamicTable() throws IOException {
        HPackContext hpack = new HPackContext();
        hpack.decode(incrementalBlock(1, 3000, 3000));

        assertNull(hpack.get(62),
            "An entry larger than the table capacity must not be stored");
    }

    private static byte[] incrementalBlock(int count, int nameLen, int valueLen) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] name = new byte[nameLen];
        Arrays.fill(name, (byte) 'x');
        byte[] value = new byte[valueLen];
        Arrays.fill(value, (byte) 'y');
        for (int i = 0; i < count; i++) {
            out.write(0x40); // literal with incremental indexing, new name
            writeString(out, name);
            writeString(out, value);
        }
        return out.toByteArray();
    }

    private static void writeString(ByteArrayOutputStream out, byte[] bytes) {
        int length = bytes.length;
        if (length < 127) {
            out.write(length);
        } else {
            out.write(0x7F);
            int remaining = length - 127;
            while (remaining >= 128) {
                out.write((remaining & 0x7F) | 0x80);
                remaining >>>= 7;
            }
            out.write(remaining);
        }
        out.writeBytes(bytes);
    }
}
