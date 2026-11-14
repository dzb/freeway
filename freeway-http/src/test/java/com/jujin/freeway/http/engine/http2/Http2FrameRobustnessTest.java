package com.jujin.freeway.http.engine.http2;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Frame decoding is the first HTTP/2 boundary: arbitrary headers and payloads
 * must either decode or fail as a protocol/I/O error, never hang or leak an
 * unchecked exception.
 */
class Http2FrameRobustnessTest {

    @Test
    void arbitraryFrameBytesOnlyFailAsProtocolErrors() {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            Random random = new Random(0xF2A3_2026L);
            for (int iteration = 0; iteration < 2_000; iteration++) {
                byte[] raw = new byte[random.nextInt(257)];
                random.nextBytes(raw);
                decode(raw);
            }
        });
    }

    @Test
    void structuredFramesWithRandomHeadersOnlyFailAsProtocolErrors() {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            Random random = new Random(0xF2B4_2026L);
            for (int iteration = 0; iteration < 2_000; iteration++) {
                int length = random.nextInt(257);
                byte[] body = new byte[length];
                random.nextBytes(body);
                byte[] frame = new byte[9 + length];
                frame[0] = (byte) (length >>> 16);
                frame[1] = (byte) (length >>> 8);
                frame[2] = (byte) length;
                frame[3] = (byte) random.nextInt(16);       // type, incl. unknown
                frame[4] = (byte) random.nextInt(256);      // flags
                int streamId = random.nextInt();
                frame[5] = (byte) (streamId >>> 24);
                frame[6] = (byte) (streamId >>> 16);
                frame[7] = (byte) (streamId >>> 8);
                frame[8] = (byte) streamId;
                System.arraycopy(body, 0, frame, 9, length);
                decode(frame);
            }
        });
    }

    private static void decode(byte[] raw) {
        try {
            FrameSerializer.deserialize(new ByteArrayInputStream(raw));
        } catch (IOException expected) {
            // EOF, FRAME_SIZE_ERROR, PROTOCOL_ERROR and friends.
        }
    }
}
