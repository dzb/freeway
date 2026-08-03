package com.jujin.freeway.db.internal;

import com.jujin.freeway.db.DatabaseStats;
import com.jujin.freeway.db.Pool;
import com.jujin.freeway.db.PoolConfig;
import com.jujin.freeway.db.PooledConnection;
import com.jujin.freeway.db.SqlException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class PoolDefault implements Pool {

    private static final Logger LOG = LoggerFactory.getLogger(
        PoolDefault.class
    );
    private static final Duration FRESH_IDLE_THRESHOLD = Duration.ofSeconds(5);
    private static final Duration LEAK_THRESHOLD = Duration.ofSeconds(30);

    private final PoolConfig config;
    private final Semaphore semaphore;
    private final ConcurrentLinkedDeque<PooledConnectionDefault> idle;
    private final ConcurrentLinkedDeque<PooledConnectionDefault> active;
    private final AtomicInteger total;
    private final AtomicLong borrowCount;
    private final AtomicLong borrowWaitNanos;
    private volatile boolean closed;
    private Thread cleanThread;

    public PoolDefault(PoolConfig config) {
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

    @Override
    public PooledConnectionDefault borrow() {
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
            PooledConnectionDefault conn = idle.pollFirst();
            if (conn != null) {
                // A freshly returned connection skips the full health check,
                // but must still not be closed at the JDBC level.
                if (
                    (conn.isFresh(FRESH_IDLE_THRESHOLD) && !isClosed(conn)) ||
                    isValid(conn)
                ) {
                    success = true;
                    conn.markBorrowed();
                    active.add(conn);
                    recordBorrow(waitStart);
                    return conn;
                }
                // Replace the stale connection BEFORE destroying it, so the
                // pool never transiently drops to zero connections. Databases
                // that drop state when their last connection closes (e.g. H2
                // in-memory without DB_CLOSE_DELAY=-1) would lose the database.
                PooledConnectionDefault stale = conn;
                try {
                    conn = createConnection();
                } catch (RuntimeException e) {
                    // Do not leak the stale connection if the replacement fails.
                    destroy(stale);
                    throw e;
                }
                total.incrementAndGet();
                destroy(stale);
            } else {
                conn = createConnection();
                total.incrementAndGet();
            }
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

    @Override
    public void release(PooledConnection conn) {
        PooledConnectionDefault pc = (PooledConnectionDefault) Objects.requireNonNull(conn, "conn");
        if (!active.remove(pc)) {
            // Already removed (e.g. force-closed during shutdown)
            return;
        }
        if (closed || !isAlive(pc)) {
            destroy(pc);
            semaphore.release();
            return;
        }
        pc.markReturned();
        idle.offerFirst(pc);
        semaphore.release();
    }

    @Override
    public DatabaseStats stats() {
        int longLeased = 0;
        for (PooledConnectionDefault conn : active) {
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

    /**
     * Shuts down the pool in four phases:
     * 1. Stop the cleaner thread
     * 2. Close all idle connections immediately
     * 3. Wait (up to connectionTimeout) for active connections to return
     * 4. Force-close any remaining active connections
     */
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

        PooledConnectionDefault conn;
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

    /**
     * Pre-creates minIdle connections. Each acquire/release pair ensures
     * we don't overshoot maxSize — even during warmup the semaphore is
     * the single source of truth for pool capacity.
     */
    private void warmUp() {
        int warmed = 0;
        try {
            for (int i = 0; i < config.minIdle(); i++) {
                if (!semaphore.tryAcquire()) {
                    throw new SqlException(
                        "Failed to warm up connection pool: no permits available"
                    );
                }
                try {
                    PooledConnectionDefault conn = createConnection();
                    total.incrementAndGet();
                    idle.offerFirst(conn);
                    warmed++;
                } finally {
                    semaphore.release();
                }
            }
        } catch (RuntimeException e) {
            closeWarmUpConnections(warmed);
            throw new SqlException("Failed to warm up connection pool", e);
        }
    }

    private void closeWarmUpConnections(int warmed) {
        for (int i = 0; i < warmed; i++) {
            PooledConnectionDefault conn = idle.pollFirst();
            if (conn == null) {
                break;
            }
            closePhysical(conn);
            total.decrementAndGet();
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
        List<PooledConnectionDefault> expired = new ArrayList<>();
        for (PooledConnectionDefault conn : idle) {
            if (conn.isExpired(now, config.maxLifetime(), config.maxIdleTime())) {
                expired.add(conn);
            }
        }

        if (!expired.isEmpty()) {
            int healthyIdle = idle.size() - expired.size();
            int active = Math.max(0, total.get() - idle.size());
            int capacity = Math.max(0, config.maxSize() - active - healthyIdle);
            int replacements = Math.min(
                Math.max(0, config.minIdle() - healthyIdle),
                capacity
            );

            // Create replacements BEFORE closing the expired connections.
            // Closing them all first leaves a zero-connection window; databases
            // that drop state when their last connection closes (e.g. H2
            // in-memory without DB_CLOSE_DELAY=-1) would lose the database
            // during idle cleanup.
            for (int i = 0; i < replacements; i++) {
                if (!createIdleConnection()) {
                    break;
                }
            }

            for (PooledConnectionDefault conn : expired) {
                idle.remove(conn);
                closePhysical(conn);
                total.decrementAndGet();
            }
        }

        // Top up to minIdle with remaining capacity — also covers the case
        // where connections were borrowed while the cleaner was running.
        while (idle.size() < config.minIdle() && total.get() < config.maxSize()) {
            if (!createIdleConnection()) {
                break;
            }
        }
    }

    private boolean createIdleConnection() {
        if (!semaphore.tryAcquire()) {
            return false;
        }
        try {
            PooledConnectionDefault conn = createConnection();
            total.incrementAndGet();
            idle.offerFirst(conn);
            return true;
        } catch (Exception e) {
            LOG.debug("Failed to create idle connection", e);
            return false;
        } finally {
            semaphore.release();
        }
    }

    private int healthCheckTimeoutSeconds() {
        return (int) Math.max(1, (config.healthCheckTimeout().toMillis() + 999) / 1000);
    }

    private PooledConnectionDefault createConnection() {
        try {
            Properties properties = new Properties();
            properties.setProperty("user", config.username());
            properties.setProperty("password", config.password());
            Connection conn = DriverManager.getConnection(
                config.url(),
                properties
            );
            conn.setAutoCommit(true);

            if (!conn.isValid(healthCheckTimeoutSeconds())) {
                try {
                    conn.close();
                } catch (SQLException ignored) {}
                throw new SqlException(
                    "Newly created connection failed health check: " +
                        config.url()
                );
            }
            return new PooledConnectionDefault(conn, Instant.now());
        } catch (SQLException e) {
            throw new SqlException(
                "Failed to create connection: " + e.getMessage(),
                e
            );
        }
    }

    private boolean isValid(PooledConnectionDefault conn) {
        return isAlive(conn) && healthCheck(conn);
    }

    private boolean isAlive(PooledConnectionDefault conn) {
        if (conn.isExpired(
            Instant.now(),
            config.maxLifetime(),
            config.maxIdleTime()
        )) {
            return false;
        }
        // The physical connection may have been closed out-of-band (database
        // restart, restoreConnectionState failure, driver reset). isClosed()
        // is a local flag — no network round trip — so check it before the
        // connection is recycled into the idle pool.
        try {
            return !conn.connection().isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    private boolean isClosed(PooledConnectionDefault pooled) {
        try {
            return pooled.connection().isClosed();
        } catch (SQLException e) {
            return true;
        }
    }

    private boolean healthCheck(PooledConnectionDefault pooled) {
        try {
            Connection conn = pooled.connection();
            int timeoutSec = healthCheckTimeoutSeconds();
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

    private void closePhysical(PooledConnectionDefault conn) {
        try {
            conn.connection().close();
        } catch (SQLException e) {
            LOG.trace("Error closing physical connection", e);
        }
    }

    private void destroy(PooledConnectionDefault conn) {
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
