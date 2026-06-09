package com.jujin.freeway.db.internal;

import com.jujin.freeway.db.DatabaseStats;
import com.jujin.freeway.db.SqlException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ConnectionPool implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(
        ConnectionPool.class
    );
    private static final Duration FRESH_IDLE_THRESHOLD = Duration.ofSeconds(5);
    private static final Duration LEAK_THRESHOLD = Duration.ofSeconds(30);

    private final PoolConfig config;
    private final Semaphore semaphore;
    private final ConcurrentLinkedDeque<PooledConnection> idle;
    private final ConcurrentLinkedDeque<PooledConnection> active;
    private final AtomicInteger total;
    private final AtomicLong borrowCount;
    private final AtomicLong borrowWaitNanos;
    private volatile boolean closed;
    private Thread cleanThread;

    ConnectionPool(PoolConfig config) {
        this.config = config;
        this.semaphore = new Semaphore(config.maxSize());
        this.idle = new ConcurrentLinkedDeque<>();
        this.active = new ConcurrentLinkedDeque<>();
        this.total = new AtomicInteger();
        this.borrowCount = new AtomicLong();
        this.borrowWaitNanos = new AtomicLong();
        warmUp();
        startCleaner();
    }

    PooledConnection borrow() {
        ensureOpen();
        long waitStart = System.nanoTime();
        try {
            if (
                !semaphore.tryAcquire(
                    config.connectionTimeout().toMillis(),
                    TimeUnit.MILLISECONDS
                )
            ) {
                throw new SqlException(
                    "Connection pool exhausted after " +
                        config.connectionTimeout()
                );
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SqlException(
                "Interrupted while waiting for a connection",
                e
            );
        }

        boolean success = false;
        try {
            PooledConnection conn = idle.pollFirst();
            if (conn != null) {
                if (conn.isFresh(FRESH_IDLE_THRESHOLD) || isValid(conn)) {
                    success = true;
                    conn.markBorrowed();
                    active.add(conn);
                    recordBorrow(waitStart);
                    return conn;
                }
                destroy(conn);
            }

            conn = createConnection();
            total.incrementAndGet();
            success = true;
            conn.markBorrowed();
            active.add(conn);
            recordBorrow(waitStart);
            return conn;
        } finally {
            if (!success) {
                semaphore.release();
            }
        }
    }

    void release(PooledConnection conn) {
        if (conn == null) {
            return;
        }
        if (!active.remove(conn)) {
            // Already removed (e.g. force-closed during shutdown)
            return;
        }
        if (closed || !isAlive(conn)) {
            destroy(conn);
            semaphore.release();
            return;
        }
        conn.markReturned();
        idle.offerFirst(conn);
        semaphore.release();
    }

    DatabaseStats stats() {
        int longLeased = 0;
        for (PooledConnection conn : active) {
            if (conn.isLeaked(LEAK_THRESHOLD)) {
                longLeased++;
            }
        }
        return new DatabaseStats(
            active.size(),
            idle.size(),
            total.get(),
            semaphore.getQueueLength(),
            config.maxSize(),
            longLeased,
            borrowCount.get(),
            borrowWaitNanos.get()
        );
    }

    @Override
    public void close() {
        closed = true;

        if (cleanThread != null && cleanThread != Thread.currentThread()) {
            cleanThread.interrupt();
            try {
                cleanThread.join(config.connectionTimeout().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        PooledConnection conn;
        while ((conn = idle.pollFirst()) != null) {
            closePhysical(conn);
            total.decrementAndGet();
        }

        // Wait for active connections to be returned
        long deadline =
            System.nanoTime() + config.connectionTimeout().toNanos();
        while (total.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Close any remaining idle connections (may have been returned during wait)
        while ((conn = idle.pollFirst()) != null) {
            closePhysical(conn);
            total.decrementAndGet();
        }

        // Force-close any still-active connections
        while ((conn = active.pollFirst()) != null) {
            closePhysical(conn);
            total.decrementAndGet();
        }

        int remaining = total.get();
        if (remaining > 0) {
            LOG.warn(
                "Database closed with {} connection(s) still tracked",
                remaining
            );
        }
    }

    private void warmUp() {
        for (int i = 0; i < config.minIdle(); i++) {
            if (!semaphore.tryAcquire()) {
                break;
            }
            try {
                PooledConnection conn = createConnection();
                total.incrementAndGet();
                idle.offerFirst(conn);
                semaphore.release();
            } catch (Exception e) {
                semaphore.release();
                break;
            }
        }
    }

    private void startCleaner() {
        cleanThread = Thread.ofVirtual()
            .name("freeway-db-cleaner")
            .start(() -> {
                while (!closed) {
                    try {
                        Thread.sleep(config.cleanInterval().toMillis());
                        if (closed) {
                            break;
                        }
                        clean();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });
    }

    private void clean() {
        Instant now = Instant.now();
        var it = idle.iterator();
        while (it.hasNext()) {
            PooledConnection conn = it.next();
            if (
                conn.isExpired(now, config.maxLifetime(), config.maxIdleTime())
            ) {
                it.remove();
                closePhysical(conn);
                total.decrementAndGet();
            }
        }

        int needed = config.minIdle() - idle.size();
        for (int i = 0; i < needed; i++) {
            if (total.get() >= config.maxSize()) {
                break;
            }
            if (!semaphore.tryAcquire()) {
                break;
            }
            try {
                PooledConnection conn = createConnection();
                total.incrementAndGet();
                idle.offerFirst(conn);
                semaphore.release();
            } catch (Exception e) {
                semaphore.release();
                break;
            }
        }
    }

    private PooledConnection createConnection() {
        try {
            Properties properties = new Properties();
            properties.setProperty("user", config.username());
            properties.setProperty("password", config.password());
            Connection conn = DriverManager.getConnection(
                config.url(),
                properties
            );
            conn.setAutoCommit(true);

            int healthTimeoutSec = (int) Math.max(
                1,
                (config.healthCheckTimeout().toMillis() + 999) / 1000
            );
            if (!conn.isValid(healthTimeoutSec)) {
                try {
                    conn.close();
                } catch (SQLException ignored) {}
                throw new SqlException(
                    "Newly created connection failed health check: " +
                        config.url()
                );
            }
            return new PooledConnection(conn, Instant.now());
        } catch (SQLException e) {
            throw new SqlException(
                "Failed to create connection: " + e.getMessage(),
                e
            );
        }
    }

    private boolean isValid(PooledConnection conn) {
        return isAlive(conn) && healthCheck(conn);
    }

    private boolean isAlive(PooledConnection conn) {
        return !conn.isExpired(
            Instant.now(),
            config.maxLifetime(),
            config.maxIdleTime()
        );
    }

    private boolean healthCheck(PooledConnection pooled) {
        try {
            Connection conn = pooled.connection();
            int timeoutSec = (int) Math.max(
                1,
                (config.healthCheckTimeout().toMillis() + 999) / 1000
            );
            if (!conn.isValid(timeoutSec)) {
                return false;
            }
            String query = config.healthCheckQuery();
            if (query != null && !query.isBlank()) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.setQueryTimeout(timeoutSec);
                    stmt.execute(query);
                }
            }
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private void closePhysical(PooledConnection conn) {
        try {
            conn.connection().close();
        } catch (SQLException e) {
            LOG.trace("Error closing physical connection", e);
        }
    }

    private void destroy(PooledConnection conn) {
        closePhysical(conn);
        total.decrementAndGet();
    }

    private void recordBorrow(long waitStart) {
        borrowCount.incrementAndGet();
        borrowWaitNanos.addAndGet(System.nanoTime() - waitStart);
    }

    private void ensureOpen() {
        if (closed) {
            throw new SqlException("Database is closed");
        }
    }
}
