package com.jujin.freeway.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The pool configuration is a value with named knobs: one factory states every
 * default, the withers change one field each, and validation runs on every
 * change (each wither builds a new record).
 */
class PoolConfigTest {

    @Test
    void defaultsStateEveryValueOnce() {
        PoolConfig config = PoolConfig.defaults("jdbc:h2:mem:x", "sa", "");

        assertEquals(10, config.maxSize());
        assertEquals(2, config.minIdle());
        assertEquals(Duration.ofSeconds(10), config.connectionTimeout());
        assertEquals(Duration.ofMinutes(30), config.maxLifetime());
        assertEquals(Duration.ofMinutes(10), config.maxIdleTime());
        assertEquals(Duration.ofMinutes(2), config.cleanInterval());
        assertNull(config.healthCheckQuery(), "no query: JDBC isValid decides");
        assertEquals(Duration.ofSeconds(5), config.healthCheckTimeout());
        assertEquals(Duration.ofSeconds(15), config.queryTimeout());
    }

    @Test
    void withersChangeOneFieldAndKeepTheRest() {
        PoolConfig base = PoolConfig.defaults("jdbc:h2:mem:x", "sa", "");
        PoolConfig tuned = base
            .withMaxSize(20)
            .withMinIdle(5)
            .withMaxLifetime(Duration.ofMinutes(45))
            .withHealthCheckQuery("select 1");

        assertEquals(20, tuned.maxSize());
        assertEquals(5, tuned.minIdle());
        assertEquals(Duration.ofMinutes(45), tuned.maxLifetime());
        assertEquals("select 1", tuned.healthCheckQuery());
        assertEquals(base.connectionTimeout(), tuned.connectionTimeout());
        assertEquals(base.url(), tuned.url());
        assertEquals(10, base.maxSize(), "the original is untouched");
    }

    @Test
    void everyWitherRevalidates() {
        // Validation lives in the compact constructor, so a wither cannot walk
        // the value into a state the constructor would have refused.
        PoolConfig base = PoolConfig.defaults("jdbc:h2:mem:x", "sa", "");
        assertThrows(IllegalArgumentException.class, () -> base.withMinIdle(11));
        assertThrows(IllegalArgumentException.class, () -> base.withMaxSize(0));
        assertThrows(IllegalArgumentException.class,
            () -> base.withConnectionTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> base.withQueryTimeout(null));
    }
}
