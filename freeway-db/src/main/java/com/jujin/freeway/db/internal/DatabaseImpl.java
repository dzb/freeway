package com.jujin.freeway.db.internal;

import com.jujin.freeway.db.*;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DatabaseImpl implements Database {

    private static final Logger LOG = LoggerFactory.getLogger(
        DatabaseImpl.class
    );
    private static final ScopedValue<TransactionContext> CURRENT_TX =
        ScopedValue.newInstance();

    private final ConnectionPool pool;
    private final RowMapperResolver rowMapperResolver;
    private final int queryTimeoutSeconds;

    public DatabaseImpl(
        DatabaseConfig config,
        RowMapperResolver rowMapperResolver
    ) {
        this.pool = new ConnectionPool(PoolConfig.from(config));
        this.rowMapperResolver = rowMapperResolver;
        long millis = config.queryTimeout().toMillis();
        this.queryTimeoutSeconds = (int) Math.max(1, (millis + 999) / 1000);
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
        return CURRENT_TX.isBound() ? CURRENT_TX.get().conn : null;
    }

    @Override
    public void transaction(Transactional work) {
        transaction(null, work);
    }

    @Override
    public void transaction(IsolationLevel isolation, Transactional work) {
        if (CURRENT_TX.isBound()) {
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
            TransactionContext ctx = new TransactionContext(
                conn,
                originalIsolation
            );
            ScopedValue.where(CURRENT_TX, ctx).run(() -> {
                try {
                    work.run();
                } catch (Exception e) {
                    throw e instanceof RuntimeException re
                        ? re
                        : new RuntimeException(e);
                }
            });
            raw.commit();
            LOG.trace("Transaction committed");
            for (Runnable hook : ctx.hooks()) {
                try {
                    hook.run();
                } catch (Exception ex) {
                    LOG.warn("afterCommit hook failed", ex);
                }
            }
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
            LOG.trace("Error restoring connection state", e);
        }
    }

    static boolean startsWithInsert(String sql) {
        if (sql == null) {
            return false;
        }
        int index = skipIgnorableSqlPrefix(sql);
        int end = index + "insert".length();
        return (
            end <= sql.length() &&
            sql.regionMatches(true, index, "insert", 0, "insert".length()) &&
            (end == sql.length() || !isIdentifierChar(sql.charAt(end)))
        );
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

    ConnectionPool pool() {
        return pool;
    }

    private record TransactionContext(
        PooledConnection conn,
        int originalIsolation,
        List<Runnable> hooks
    ) {
        TransactionContext(PooledConnection conn, int originalIsolation) {
            this(conn, originalIsolation, new ArrayList<>());
        }
    }

    /**
     * Register an action to run after the current transaction commits successfully.
     * If not inside a transaction, the action runs immediately.
     */
    public static void afterCommit(Runnable action) {
        TransactionContext ctx = CURRENT_TX.orElse(null);
        if (ctx != null) ctx.hooks().add(action);
        else action.run();
    }

    private static int skipIgnorableSqlPrefix(String sql) {
        int index = 0;
        while (index < sql.length()) {
            char c = sql.charAt(index);
            if (Character.isWhitespace(c)) {
                index++;
                continue;
            }
            if (
                c == '-' &&
                index + 1 < sql.length() &&
                sql.charAt(index + 1) == '-'
            ) {
                index += 2;
                while (index < sql.length() && sql.charAt(index) != '\n') {
                    index++;
                }
                continue;
            }
            if (
                c == '/' &&
                index + 1 < sql.length() &&
                sql.charAt(index + 1) == '*'
            ) {
                index += 2;
                while (index < sql.length()) {
                    char bc = sql.charAt(index);
                    index++;
                    if (
                        bc == '*' &&
                        index < sql.length() &&
                        sql.charAt(index) == '/'
                    ) {
                        index++;
                        break;
                    }
                }
                continue;
            }
            return index;
        }
        return index;
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
