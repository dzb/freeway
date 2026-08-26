package com.jujin.freeway.db.internal;

import com.jujin.freeway.commons.scoped.Defer;
import com.jujin.freeway.db.BatchQuery;
import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.DatabaseStats;
import com.jujin.freeway.db.ExecuteResult;
import com.jujin.freeway.db.IsolationLevel;
import com.jujin.freeway.db.Pool;
import com.jujin.freeway.db.PoolConfig;
import com.jujin.freeway.db.PooledConnection;
import com.jujin.freeway.db.Query;
import com.jujin.freeway.db.SqlException;
import com.jujin.freeway.db.Transactional;
import com.jujin.freeway.db.dialect.Dialect;
import com.jujin.freeway.db.dialect.PostgresDialect;
import com.jujin.freeway.db.util.SqlTextParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class DatabaseImpl implements Database {

    private static final Logger LOG = LoggerFactory.getLogger(
        DatabaseImpl.class
    );

    /**
     * Transaction binding for this Database instance. Instance-scoped so a
     * transaction on one Database never leaks into another Database's
     * queries (a static key made {@code db1.transaction(() -> hub.get("audit").query(...))}
     * silently execute audit's SQL on db1's connection).
     */
    private final ScopedValue<TxBinding> tx = ScopedValue.newInstance();

    /**
     * Threads currently inside a transaction on this Database. Added when
     * {@code transaction()} starts and removed in its {@code finally}.
     * ScopedValue does not propagate to child threads, so this registry is
     * what lets the borrow guard reject DB calls that would otherwise
     * silently borrow an independent pooled connection and run outside the
     * transaction. A set, not a single field: parallel transactions started
     * by different threads on the same Database (legal — each borrows its own
     * connection) must not overwrite each other's registration, which would
     * both defeat the guard early and make {@link #inTransaction()} lie.
     */
    private final Set<Thread> activeTxThreads = ConcurrentHashMap.newKeySet();

    private final Pool pool;
    private final RowMapperResolver rowMapperResolver;
    private final Dialect dialect;
    private final int queryTimeoutSeconds;

    public DatabaseImpl(
        PoolConfig config,
        RowMapperResolver rowMapperResolver
    ) {
        this(config, rowMapperResolver, null, null);
    }

    public DatabaseImpl(
        PoolConfig config,
        RowMapperResolver rowMapperResolver,
        Pool pool,
        Dialect dialect
    ) {
        this.pool =
            pool != null ? pool : new PoolDefault(config);
        this.rowMapperResolver = rowMapperResolver;
        this.dialect = dialect != null ? dialect : new PostgresDialect();
        long millis = config.queryTimeout().toMillis();
        // 0 = no timeout (JDBC setQueryTimeout(0)); sub-second values round up
        // to a whole second.
        this.queryTimeoutSeconds = (int) Math.max(0, (millis + 999) / 1000);
    }

    @Override
    public Dialect dialect() {
        return dialect;
    }

    @Override
    public boolean inTransaction() {
        return activeTxThreads.contains(Thread.currentThread());
    }

    /** The caller's transaction binding, or null when outside a transaction. */
    private TxBinding currentTx() {
        return tx.isBound() ? tx.get() : null;
    }

    @Override
    public Query query(String sql, Object... params) {
        return new QueryImpl(
            this,
            currentTx(),
            sql,
            params,
            false
        );
    }

    @Override
    public ExecuteResult execute(String sql, Object... params) {
        return new QueryImpl(
            this,
            currentTx(),
            sql,
            params,
            SqlTextParser.hasTopLevelInsert(sql, dialect)
        ).execute();
    }

    @Override
    public BatchQuery batch(String sql) {
        return new BatchQueryImpl(
            this,
            currentTx(),
            sql
        );
    }

    @Override
    public void transaction(Transactional work) {
        transaction(null, work);
    }

    /**
     * Runs {@code work} inside a JDBC transaction.
     *
     * <p>The transaction wraps via {@link Defer#within} so events published
     * during the work are buffered and only drained after commit — if the
     * transaction rolls back, the events are discarded.
     *
     * <p>The transaction covers only this {@code Database}'s connection: SQL
     * executed on other {@code Database} instances (e.g. obtained from a
     * {@link DatabaseHub}) commits independently and is not rolled back when
     * this transaction fails.
     */
    @Override
    public void transaction(IsolationLevel isolation, Transactional work) {
        if (tx.isBound()) {
            throw new IllegalStateException("Nested transaction not supported");
        }
        PooledConnection conn = pool.borrow();
        int originalIsolation = -1;
        activeTxThreads.add(Thread.currentThread());
        try {
            var raw = conn.connection();
            originalIsolation = raw.getTransactionIsolation();
            raw.setAutoCommit(false);
            if (isolation != null && isolation != IsolationLevel.DEFAULT) {
                raw.setTransactionIsolation(isolation.jdbcLevel());
            }
            TxBinding binding = new TxBinding(conn);
            Defer.within(() -> {
                ScopedValue.where(tx, binding).run(() -> {
                    try {
                        work.run();
                    } catch (Exception e) {
                        throw e instanceof RuntimeException re
                            ? re
                            : new RuntimeException(e);
                    }
                });
                try {
                    raw.commit();
                } catch (SQLException e) {
                    throw new RuntimeException("Commit failed", e);
                }
                LOG.trace("Transaction committed");
            });
        } catch (Throwable e) {
            // Catch Throwable, not just Exception: an Error thrown by work
            // (e.g. AssertionError) must still roll back — the finally's
            // restoreConnectionState setAutoCommit(true) would otherwise
            // silently COMMIT the failed transaction.
            LOG.debug("Transaction rolled back", e);
            try {
                conn.connection().rollback();
            } catch (SQLException re) {
                LOG.warn("Transaction rollback failed", re);
            }
            if (e instanceof RuntimeException re) throw re;
            if (e instanceof Error err) throw err;
            throw new RuntimeException(e);
        } finally {
            activeTxThreads.remove(Thread.currentThread());
            restoreConnectionState(conn, originalIsolation);
            pool.release(conn);
        }
    }

    private void restoreConnectionState(
        PooledConnection conn,
        int originalIsolation
    ) {
        try {
            var raw = conn.connection();
            if (
                originalIsolation >= 0 &&
                raw.getTransactionIsolation() != originalIsolation
            ) {
                raw.setTransactionIsolation(originalIsolation);
            }
            if (!raw.getAutoCommit()) {
                raw.setAutoCommit(true);
            }
        } catch (SQLException e) {
            LOG.warn("Failed to restore connection state — closing physical connection", e);
            closePhysical(conn);
        }
    }

    private static void closePhysical(PooledConnection conn) {
        try {
            conn.connection().close();
        } catch (SQLException ignored) {
            // physical close is best-effort
        }
    }

    @Override
    public boolean ping() {
        try {
            PooledConnection conn = pool.borrow();
            try {
                return conn.connection().isValid(5);
            } finally {
                pool.release(conn);
            }
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public DatabaseStats stats() {
        return pool.stats();
    }

    @Override
    public void close() {
        pool.close();
    }

    int queryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    RowMapperResolver rowMapperResolver() {
        return rowMapperResolver;
    }

    Pool pool() {
        return pool;
    }

    /**
     * Immutable identity handle for one transaction on this Database. Queries
     * and batches created inside a transaction capture the binding and are
     * validated against it at consumption time.
     */
    static final class TxBinding {

        private final PooledConnection conn;

        TxBinding(PooledConnection conn) {
            this.conn = conn;
        }

        PooledConnection conn() {
            return conn;
        }
    }

    /**
     * Guards against consuming a Query/BatchQuery created inside a
     * transaction after that transaction has released its connection, or on
     * a different thread (ScopedValue does not propagate). Identity-compared
     * against the current thread's binding, so concurrent transactions on
     * other threads never invalidate this one.
     */
    void checkBound(TxBinding binding) {
        if (!tx.isBound() || tx.get() != binding) {
            throw new SqlException(
                "Query/BatchQuery created inside a transaction must be consumed "
                    + "on the transaction thread before the transaction ends — "
                    + "the pooled connection is not bound to this thread"
            );
        }
    }

    /**
     * Guards against DB calls made on a different thread while a transaction
     * is active on this Database. ScopedValue does not propagate to child
     * threads, so a child thread's query/execute would otherwise borrow an
     * independent pooled connection and run outside the transaction —
     * silently breaking atomicity (and surfacing as a misleading "pool
     * exhausted" error when maxSize = 1). Called at the pool-borrow entry
     * points for unbound work; work bound to a transaction already goes
     * through {@link #checkBound} on the transaction thread.
     */
    void checkNoForeignTransaction() {
        Thread current = Thread.currentThread();
        if (!activeTxThreads.isEmpty() && !activeTxThreads.contains(current)) {
            throw new SqlException(
                "Another thread holds an active transaction on this Database, "
                    + "and DB calls from other threads are rejected while it runs "
                    + "(ScopedValue does not propagate; the call would borrow an "
                    + "independent pooled connection and run outside the transaction). "
                    + "Run DB work on the transaction thread, or use a separate "
                    + "Database instance (e.g. via DatabaseHub) for concurrent work"
            );
        }
    }

}
