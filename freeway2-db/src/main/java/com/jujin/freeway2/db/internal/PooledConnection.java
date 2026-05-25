package com.jujin.freeway2.db.internal;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;

public final class PooledConnection {
    private final Connection jdbcConnection;
    private final Instant createdAt;
    private volatile Instant lastReturned;

    PooledConnection(Connection jdbcConnection, Instant createdAt) {
        this.jdbcConnection = jdbcConnection;
        this.createdAt = createdAt;
        this.lastReturned = createdAt;
    }

    public Connection jdbcConnection() {
        return jdbcConnection;
    }

    void markReturned() {
        lastReturned = Instant.now();
    }

    boolean isFresh(Duration threshold) {
        return Duration.between(lastReturned, Instant.now()).compareTo(threshold) < 0;
    }

    boolean isExpired(Instant now, Duration maxLifetime, Duration maxIdleTime) {
        return Duration.between(createdAt, now).compareTo(maxLifetime) > 0
            || Duration.between(lastReturned, now).compareTo(maxIdleTime) > 0;
    }
}
