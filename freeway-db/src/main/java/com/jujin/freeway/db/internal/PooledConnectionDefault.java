package com.jujin.freeway.db.internal;

import com.jujin.freeway.db.PooledConnection;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;

public final class PooledConnectionDefault implements PooledConnection {

    private final Connection conn;
    private final Instant createdAt;
    private volatile Instant lastReturned;
    private volatile Instant borrowedAt; // null when idle, non-null when borrowed

    public PooledConnectionDefault(Connection conn, Instant createdAt) {
        this.conn = conn;
        this.createdAt = createdAt;
        this.lastReturned = createdAt;
    }

    public Connection connection() {
        return conn;
    }

    void markBorrowed() {
        this.borrowedAt = Instant.now();
    }

    void markReturned() {
        this.lastReturned = Instant.now();
        this.borrowedAt = null;
    }

    /**
     * 连接被借出的时刻，null 表示空闲中。
     */
    Instant borrowedAt() {
        return borrowedAt;
    }

    boolean isLeaked(Duration leakThreshold) {
        Instant ba = borrowedAt;
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
