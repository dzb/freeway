package com.jujin.freeway.db;

/**
 * Connection-pool abstraction. Implementations provide pooled
 * {@link PooledConnection} instances via {@link #borrow()} and
 * accept them back via {@link #release(PooledConnection)}.
 *
 * @see PoolConfig
 * @see PoolDefault
 */
public interface Pool extends AutoCloseable {

    /**
     * Borrows a connection from the pool. Blocks until one is available
     * or the configured {@link PoolConfig#connectionTimeout()} elapses.
     *
     * @return a pooled connection (must be released back)
     */
    PooledConnection borrow();

    /**
     * Returns a borrowed connection to the pool.
     *
     * @param conn the connection to release
     */
    void release(PooledConnection conn);

    /**
     * Returns pool statistics.
     */
    DatabaseStats stats();

    @Override
    void close();
}
