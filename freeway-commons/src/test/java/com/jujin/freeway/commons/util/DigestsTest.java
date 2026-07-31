package com.jujin.freeway.commons.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DigestsTest {

    @Test
    void sha256HexMatchesKnownVector() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Digests.sha256Hex(new byte[0])
        );
    }

    @Test
    void sha256HexRepeatedCallsAreConsistent() {
        byte[] data = "freeway".getBytes(StandardCharsets.UTF_8);
        assertEquals(
            "bac816c9cb0330a07e170bc92c9b322563d4dbd1a2ba659d73226c074bcaa996",
            Digests.sha256Hex(data)
        );
        assertEquals(64, Digests.sha256Hex(data).length());
    }

    @Test
    void sha256Base64IsUrlSafeWithoutPadding() {
        assertEquals(
            "LPJNul-wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ",
            Digests.sha256Base64("hello".getBytes(StandardCharsets.UTF_8))
        );
    }
}
