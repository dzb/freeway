package com.jujin.freeway.http;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void builderDefaultsMatchCanonicalConstructor() {
        HttpServerConfig fromBuilder = HttpServerConfig.builder().build();
        HttpServerConfig canonical = new HttpServerConfig(
            "127.0.0.1", 0, 0, Duration.ZERO, HttpServerConfig.DEFAULT_MAX_BODY_SIZE,
            HttpServerConfig.DEFAULT_READ_TIMEOUT, HttpServerConfig.DEFAULT_MAX_CONNECTIONS,
            HttpServerConfig.DEFAULT_WRITE_TIMEOUT,
            HttpServerConfig.CompressionConfig.DEFAULT, 0, 0);

        assertEquals(canonical, fromBuilder,
            "a builder with no explicit values must equal the canonical defaults");
    }

    @Test
    void builderAppliesExplicitValues() {
        HttpServerConfig cfg = HttpServerConfig.builder()
            .host("0.0.0.0")
            .port(8080)
            .shutdownGrace(Duration.ofSeconds(2))
            .maxBodySize(2048)
            .readTimeout(Duration.ofSeconds(5))
            .maxConnections(100)
            .writeTimeout(Duration.ofSeconds(7))
            .compression(new HttpServerConfig.CompressionConfig(false, 0))
            .receiveBufferSize(4096)
            .sendBufferSize(8192)
            .build();

        assertEquals("0.0.0.0", cfg.host());
        assertEquals(8080, cfg.port());
        assertEquals(Duration.ofSeconds(2), cfg.shutdownGrace());
        assertEquals(2048, cfg.maxBodySize());
        assertEquals(Duration.ofSeconds(5), cfg.readTimeout());
        assertEquals(100, cfg.maxConnections());
        assertEquals(Duration.ofSeconds(7), cfg.writeTimeout());
        assertEquals(new HttpServerConfig.CompressionConfig(false, 0), cfg.compression());
        assertEquals(4096, cfg.receiveBufferSize());
        assertEquals(8192, cfg.sendBufferSize());
    }

    @Test
    void builderRunsTheSameValidationAsTheConstructor() {
        assertThrows(IllegalArgumentException.class,
            () -> HttpServerConfig.builder().readTimeout(Duration.ofSeconds(-1)).build());
        assertThrows(IllegalArgumentException.class,
            () -> HttpServerConfig.builder().maxBodySize(0).build());
        assertThrows(IllegalArgumentException.class,
            () -> HttpServerConfig.builder().port(70_000).build());
        assertThrows(IllegalArgumentException.class,
            () -> HttpServerConfig.builder().backlog(-1).build());
    }

    private static HttpServerConfig config(Duration read, Duration write) {
        return new HttpServerConfig("127.0.0.1", 0, 0,
            Duration.ofSeconds(1), 1024, read, 0, write,
            HttpServerConfig.CompressionConfig.DEFAULT, 0, 0);
    }
}
