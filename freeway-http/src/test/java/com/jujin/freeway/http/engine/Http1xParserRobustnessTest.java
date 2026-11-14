package com.jujin.freeway.http.engine;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The HTTP/1 parser sits on an untrusted byte stream: arbitrary or mutated
 * input must either parse or fail with {@link IOException}, never hang and
 * never leak an unchecked exception. Deterministic seed, no dependencies.
 */
class Http1xParserRobustnessTest {

    @Test
    void arbitraryAndMutatedInputStaysWithinTheParserContract() {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            Random random = new Random(0x5EEDL);
            for (int i = 0; i < 1_200; i++) {
                byte[] raw = new byte[random.nextInt(257)];
                random.nextBytes(raw);
                parse(raw);
            }

            byte[] valid = ("POST /echo HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "\r\n"
                + "4\r\nabcd\r\n0\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
            for (int i = 0; i < 1_200; i++) {
                byte[] mutated = valid.clone();
                int flips = 1 + random.nextInt(4);
                for (int f = 0; f < flips; f++) {
                    mutated[random.nextInt(mutated.length)] = (byte) random.nextInt(256);
                }
                parse(mutated);
            }
        });
    }

    private static void parse(byte[] raw) {
        try {
            new Http1xParser(new ByteArrayInputStream(raw)).parse();
        } catch (IOException expected) {
            // Rejected input is the contract; anything else is a defect.
        }
    }
}
