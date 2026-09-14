package com.jujin.freeway.http;

import java.time.Duration;

/**
 * Test-only server configurations.
 *
 * <p>Most HTTP tests want the same thing: a loopback server on an ephemeral
 * port with a short shutdown grace, so the suite neither collides on ports nor
 * waits for graceful shutdown. That shape used to be spelled out as
 * {@code new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2))} at
 * ~60 call sites — the four-positional-argument form the config deleted. It
 * lives here now, in test code, where a test convenience belongs.</p>
 */
public final class TestServerConfig {

    /** Loopback, OS-chosen port, 2s shutdown grace, everything else default. */
    public static HttpServerConfig loopback() {
        return HttpServerConfig.defaults().withPort(0);
    }

    /** As {@link #loopback()} on an explicit port. */
    public static HttpServerConfig loopback(int port) {
        return HttpServerConfig.defaults().withPort(port);
    }

    private TestServerConfig() {}
}
