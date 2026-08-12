package com.jujin.freeway.http.engine;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkedInputStreamTest {

    @Test
    void readsChunkedBodyWithTrailers() throws Exception {
        // "3\r\nabc\r\n0\r\nX-Trailer: ok\r\n\r\n"
        byte[] raw = "3\r\nabc\r\n0\r\nX-Trailer: ok\r\n\r\n".getBytes();
        var in = new ChunkedInputStream(new ByteArrayInputStream(raw));

        byte[] buf = new byte[10];
        int n = in.read(buf, 0, 10);
        assertEquals(3, n, "Should read 3 body bytes");
        assertEquals("abc", new String(buf, 0, 3));

        // After body, read should return -1 (EOF) without IOException
        assertEquals(-1, in.read(buf, 0, 10),
                "Trailers should be consumed, not cause IOException");
    }

    @Test
    void singleByteReadsWalkChunkedBody() throws Exception {
        byte[] raw = "5\r\nhello\r\n0\r\n\r\n".getBytes();
        var in = new ChunkedInputStream(new ByteArrayInputStream(raw));

        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            sb.append((char) b);
        }
        assertEquals("hello", sb.toString(),
                "read() must deliver body bytes one at a time through the chunk parser");
    }

    @Test
    void chunkLengthOverflowThrowsIOException() {
        // "80000000" hex = 2^31 — one past Integer.MAX_VALUE. The parser must
        // fail loudly instead of wrapping around to a small int (which would
        // desynchronize the chunk framing).
        byte[] raw = "80000000\r\nabc\r\n0\r\n\r\n".getBytes();
        var in = new ChunkedInputStream(new ByteArrayInputStream(raw));

        assertThrows(IOException.class, () -> in.read(new byte[10], 0, 10),
                "Chunk size beyond 2^31-1 must raise IOException");
    }

    @Test
    void rejectsEmptyChunkSize() {
        var in = new ChunkedInputStream(new ByteArrayInputStream(";\r\n".getBytes()));
        assertThrows(IOException.class, () -> in.read(new byte[1], 0, 1));
    }

    @Test
    void closeDrainsUnreadChunks() throws Exception {
        var raw = new ByteArrayInputStream("3\r\nabc\r\n0\r\n\r\n".getBytes());
        var in = new ChunkedInputStream(raw);
        in.close();
        assertEquals(0, raw.available(), "close must consume the remaining chunk framing");
        assertThrows(IOException.class, () -> in.read(new byte[1], 0, 1));
    }
}
