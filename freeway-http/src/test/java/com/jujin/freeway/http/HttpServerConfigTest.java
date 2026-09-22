package com.jujin.freeway.http;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void defaultsAreTheDeclaredModuleDefaults() {
        // The defaults used to be stated in three places (the config keys, the
        // standalone builder, the server constructor) and had drifted: the
        // builder said port 0 / grace 0 while the module declares 8080 / 2s.
        // One statement now, and one assembly path to read it from.
        HttpServerConfig defaults = HttpServerConfig.defaults();

        assertEquals(HttpServerConfig.DEFAULT_HOST, defaults.host());
        assertEquals(8080, defaults.port());
        assertEquals(0, defaults.backlog());
        assertEquals(Duration.ofSeconds(2), defaults.shutdownGrace());
        assertEquals(HttpServerConfig.DEFAULT_MAX_BODY_SIZE, defaults.maxBodySize());
        assertEquals(HttpServerConfig.DEFAULT_READ_TIMEOUT, defaults.readTimeout());
        assertEquals(HttpServerConfig.DEFAULT_WRITE_TIMEOUT, defaults.writeTimeout());
        assertEquals(HttpServerConfig.DEFAULT_MAX_CONNECTIONS, defaults.maxConnections());
        assertEquals(HttpServerConfig.CompressionConfig.DEFAULT, defaults.compression());
        assertEquals(HttpServerConfig.DEFAULT_H2_RESET_BURST_LIMIT, defaults.h2ResetBurstLimit());
        assertEquals(HttpServerConfig.DEFAULT_H2_RESET_WINDOW, defaults.h2ResetWindow());
    }

    @Test
    void eachWitherReplacesOneComponentAndLeavesTheOriginalAlone() {
        HttpServerConfig base = HttpServerConfig.defaults();

        HttpServerConfig cfg = base
            .withHost("0.0.0.0")
            .withPort(9090)
            .withBacklog(128)
            .withShutdownGrace(Duration.ofSeconds(3))
            .withMaxBodySize(2048)
            .withReadTimeout(Duration.ofSeconds(5))
            .withMaxConnections(100)
            .withWriteTimeout(Duration.ofSeconds(7))
            .withCompression(new HttpServerConfig.CompressionConfig(false, 0))
            .withReceiveBufferSize(4096)
            .withSendBufferSize(8192)
            .withH2ResetBurstLimit(50)
            .withH2ResetWindow(Duration.ofSeconds(5));

        assertEquals("0.0.0.0", cfg.host());
        assertEquals(9090, cfg.port());
        assertEquals(128, cfg.backlog());
        assertEquals(Duration.ofSeconds(3), cfg.shutdownGrace());
        assertEquals(2048, cfg.maxBodySize());
        assertEquals(Duration.ofSeconds(5), cfg.readTimeout());
        assertEquals(100, cfg.maxConnections());
        assertEquals(Duration.ofSeconds(7), cfg.writeTimeout());
        assertEquals(new HttpServerConfig.CompressionConfig(false, 0), cfg.compression());
        assertEquals(4096, cfg.receiveBufferSize());
        assertEquals(8192, cfg.sendBufferSize());
        assertEquals(50, cfg.h2ResetBurstLimit());
        assertEquals(Duration.ofSeconds(5), cfg.h2ResetWindow());

        // A wither returns a new value: the record stays immutable, so a config
        // handed to a server cannot be changed under it.
        assertEquals("127.0.0.1", base.host());
        assertEquals(8080, base.port());
    }

    @Test
    void withersRunTheSameValidationAsTheConstructor() {
        HttpServerConfig base = HttpServerConfig.defaults();
        assertThrows(IllegalArgumentException.class,
            () -> base.withReadTimeout(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> base.withMaxBodySize(0));
        assertThrows(IllegalArgumentException.class, () -> base.withPort(70_000));
        assertThrows(IllegalArgumentException.class, () -> base.withBacklog(-1));
        assertThrows(IllegalArgumentException.class, () -> base.withH2ResetBurstLimit(-1));
        assertThrows(IllegalArgumentException.class,
            () -> base.withH2ResetWindow(Duration.ofSeconds(-1)));
    }

    @Test
    void canonicalConstructorAndDefaultsAgreeOnEveryComponent() {
        // The canonical constructor stays the one that names everything; the
        // factory is just the documented default of each component.
        HttpServerConfig canonical = new HttpServerConfig(
            HttpServerConfig.DEFAULT_HOST, HttpServerConfig.DEFAULT_PORT,
            HttpServerConfig.DEFAULT_BACKLOG, HttpServerConfig.DEFAULT_SHUTDOWN_GRACE,
            HttpServerConfig.DEFAULT_MAX_BODY_SIZE, HttpServerConfig.DEFAULT_READ_TIMEOUT,
            HttpServerConfig.DEFAULT_MAX_CONNECTIONS, HttpServerConfig.DEFAULT_WRITE_TIMEOUT,
            HttpServerConfig.CompressionConfig.DEFAULT, 0, 0,
            HttpServerConfig.DEFAULT_H2_RESET_BURST_LIMIT,
            HttpServerConfig.DEFAULT_H2_RESET_WINDOW);

        assertEquals(canonical, HttpServerConfig.defaults());
        assertSame(HttpServerConfig.CompressionConfig.DEFAULT,
            HttpServerConfig.defaults().compression());
    }

    private static HttpServerConfig config(Duration read, Duration write) {
        return HttpServerConfig.defaults()
            .withPort(0)
            .withShutdownGrace(Duration.ofSeconds(1))
            .withMaxBodySize(1024)
            .withReadTimeout(read)
            .withWriteTimeout(write);
    }
}
