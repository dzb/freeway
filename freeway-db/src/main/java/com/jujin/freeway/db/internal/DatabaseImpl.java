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
    public Query query(String sql, Object... params) {
        return new QueryImpl(
            this,
            tx.isBound() ? tx.get() : null,
            sql,
            params,
            false
        );
    }

    @Override
    public ExecuteResult execute(String sql, Object... params) {
        return new QueryImpl(
            this,
            tx.isBound() ? tx.get() : null,
            sql,
            params,
            startsWithInsert(sql, dialect)
        ).execute();
    }

    @Override
    public BatchQuery batch(String sql) {
        return new BatchQueryImpl(
            this,
            tx.isBound() ? tx.get() : null,
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
     */
    @Override
    public void transaction(IsolationLevel isolation, Transactional work) {
        if (tx.isBound()) {
            throw new IllegalStateException("Nested transaction not supported");
        }
        PooledConnection conn = pool.borrow();
        int originalIsolation = -1;
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

    static boolean startsWithInsert(String sql, Dialect dialect) {
        return SqlTextParser.hasTopLevelInsert(sql, dialect);
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

}
