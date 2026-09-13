package com.jujin.freeway.db;

/**
 * Connection-pool abstraction. Implementations provide pooled
 * {@link PooledConnection} instances via {@link #borrow()}, accept them
 * back via {@link #release(PooledConnection)}, and destroy them via
 * {@link #invalidate(PooledConnection)} when they must not be reused.
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
     * Discards a borrowed connection: the pool destroys the physical
     * connection instead of recycling it, and frees the slot it occupied.
     *
     * <p>Use this when the caller knows the connection must not reach another
     * borrower — a failed transaction, a protocol error, state that could not
     * be reset. {@link #release(PooledConnection)} is the opposite intent: it
     * returns a healthy connection for reuse, and a pool is expected to
     * recycle it. Closing {@link PooledConnection#connection()} directly
     * expresses neither: on a pool that hands out a proxy, closing the proxy
     * returns the connection to the pool rather than destroying it.
     *
     * <p>The handle is dead afterwards: {@link PooledConnection#connection()}
     * must not be used on it. {@link #release(PooledConnection)} and a repeated
     * invalidate are no-ops, so the usual cleanup order — invalidate, then
     * release in a {@code finally} — is safe; a handle belonging to another
     * pool is rejected the same way a foreign release is.
     *
     * @param conn the borrowed connection to destroy
     */
    void invalidate(PooledConnection conn);

    /**
     * Returns pool statistics.
     */
    DatabaseStats stats();

    @Override
    void close();
}
