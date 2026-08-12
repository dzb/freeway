package com.jujin.freeway.http;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpServerConfigTest {
    @Test
    void rejectsMissingOrNegativeTimeouts() {
        assertThrows(IllegalArgumentException.class, () -> config(null, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> config(Duration.ofSeconds(-1), Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> config(Duration.ZERO, null));
        assertThrows(IllegalArgumentException.class,
            () -> config(Duration.ZERO, Duration.ofSeconds(-1)));
    }

    private static HttpServerConfig config(Duration read, Duration write) {
        return new HttpServerConfig("127.0.0.1", 0, 0, 1024,
            Duration.ofSeconds(1), 1024, read, 0, write,
            HttpServerConfig.CompressionConfig.DEFAULT, 0, 0);
    }
}
