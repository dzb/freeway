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
import com.jujin.freeway.db.Transactional;
import com.jujin.freeway.db.schema.Dialect;
import com.jujin.freeway.db.schema.PostgresDialect;
import com.jujin.freeway.db.util.SqlTextParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

public final class DatabaseImpl implements Database {

    private static final Logger LOG = LoggerFactory.getLogger(
        DatabaseImpl.class
    );
    private static final ScopedValue<PooledConnection> TX_CONN =
        ScopedValue.newInstance();

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
        return new QueryImpl(this, txConnection(), sql, params, false);
    }

    @Override
    public ExecuteResult execute(String sql, Object... params) {
        return new QueryImpl(
            this,
            txConnection(),
            sql,
            params,
            startsWithInsert(sql)
        ).execute();
    }

    @Override
    public BatchQuery batch(String sql) {
        return new BatchQueryImpl(this, txConnection(), sql);
    }

    private PooledConnection txConnection() {
        return TX_CONN.isBound() ? TX_CONN.get() : null;
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
        if (TX_CONN.isBound()) {
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
            Defer.within(() -> {
                ScopedValue.where(TX_CONN, conn).run(() -> {
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
        } catch (Exception e) {
            LOG.debug("Transaction rolled back", e);
            try {
                conn.connection().rollback();
            } catch (SQLException re) {
                LOG.warn("Transaction rollback failed", re);
            }
            if (e instanceof RuntimeException re) throw re;
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

    static boolean startsWithInsert(String sql) {
        return SqlTextParser.hasTopLevelInsert(sql);
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

}
