package com.jujin.freeway.http.engine.http2.frame;

import java.io.ByteArrayInputStream;
import java.io.EOFException;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.http.engine.http2.util.Http2Exception;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

class FrameSerializerTest {

    @Test
    void deserializeRespectsDefaultMaxFrameSize() throws Exception {
        // length=16385 (0x004001) exceeds default 16384 → FRAME_SIZE_ERROR
        byte[] header = {0, 64, 1, 4, 0, 0, 0, 0, 0};
        assertThrows(Http2Exception.class, () ->
            FrameSerializer.deserialize(new ByteArrayInputStream(header)));
    }

    @Test
    void deserializeAcceptsNegotiatedMaxFrameSize() throws Exception {
        // length=16385, but negotiated max=65535 → frame size check passes
        byte[] header = {0, 64, 1, 4, 0, 0, 0, 0, 0};
        try {
            FrameSerializer.deserialize(new ByteArrayInputStream(header), 65535);
        } catch (Http2Exception e) {
            fail("Frame below negotiated max should not be rejected: " + e.getMessage());
        } catch (EOFException ignored) {
            // Expected: body bytes not provided, but frame size check passed
        }
    }

    @Test
    void deserializeRejectsFrameExceedingNegotiatedMax() throws Exception {
        byte[] header = {0, 64, 1, 4, 0, 0, 0, 0, 0}; // length=16385 > 8192
        assertThrows(Http2Exception.class, () ->
            FrameSerializer.deserialize(new ByteArrayInputStream(header), 8192));
    }
}
