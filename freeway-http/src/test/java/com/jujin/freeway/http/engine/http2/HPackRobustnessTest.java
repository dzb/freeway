package com.jujin.freeway.http.engine.http2;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * HPACK sees untrusted peer bytes: a malformed block must terminate as a
 * compression/protocol error, never as a hang or an unchecked exception.
 */
class HPackRobustnessTest {

    @Test
    void arbitraryBlocksOnlyFailAsCompressionErrors() {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            Random random = new Random(0x4AAC_2026L);
            for (int iteration = 0; iteration < 2_000; iteration++) {
                byte[] block = new byte[random.nextInt(257)];
                random.nextBytes(block);
                try {
                    new HPackContext().decode(block);
                } catch (IOException expected) {
                    // Http2Exception (COMPRESSION_ERROR) or a malformed read.
                }
            }
        });
    }

    @Test
    void arbitraryHuffmanBytesOnlyFailAsCompressionErrors() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            Random random = new Random(0x48FF_2026L);
            for (int iteration = 0; iteration < 2_000; iteration++) {
                byte[] encoded = new byte[random.nextInt(129)];
                random.nextBytes(encoded);
                try {
                    Huffman.decode(encoded);
                } catch (IOException expected) {
                    // Invalid padding / truncated code.
                }
            }
        });
    }
}
