package com.jujin.freeway.db.internal;

import com.jujin.freeway.db.PooledConnection;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;

/**
 * The built-in pool's {@link PooledConnection}: wraps one physical JDBC
 * {@link Connection} with the borrow/return bookkeeping {@link PoolDefault}
 * tracks. Each pool owns its pooled-connection type — a pool adapter brings
 * its own wrapper.
 */
final class PooledConnectionImpl implements PooledConnection {

    private final Connection conn;
    private final Instant createdAt;
    private volatile Instant lastReturned;
    private volatile Instant borrowedAt; // null when idle, non-null when borrowed

    public PooledConnectionImpl(Connection conn, Instant createdAt) {
        this.conn = conn;
        this.createdAt = createdAt;
        this.lastReturned = createdAt;
    }

    @Override
    public Connection connection() {
        return conn;
    }

    Instant createdAt() {
        return createdAt;
    }

    void markBorrowed() {
        this.borrowedAt = Instant.now();
    }

    void markReturned() {
        this.lastReturned = Instant.now();
        this.borrowedAt = null;
    }

    /**
     * Timestamp when the connection was borrowed; null when idle.
     */
    Instant borrowedAt() {
        return borrowedAt;
    }

    boolean isLeaked(Duration leakThreshold) {
        Instant ba = borrowedAt();
        return (
            ba != null &&
            Duration.between(ba, Instant.now()).compareTo(leakThreshold) > 0
        );
    }

    boolean isFresh(Duration threshold) {
        return (
            Duration.between(lastReturned, Instant.now()).compareTo(threshold) <
            0
        );
    }

    boolean isExpired(Instant now, Duration maxLifetime, Duration maxIdleTime) {
        return (
            Duration.between(createdAt, now).compareTo(maxLifetime) > 0 ||
            Duration.between(lastReturned, now).compareTo(maxIdleTime) > 0
        );
    }
}
