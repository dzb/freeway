package com.jujin.freeway.http.engine;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
