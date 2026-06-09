package com.jujin.freeway.db.internal;

import com.jujin.freeway.db.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.function.Consumer;

public final class DatabaseImpl implements Database {
    private static final Logger LOG = LoggerFactory.getLogger(DatabaseImpl.class);

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
        return new QueryImpl(this, null, sql, params, false);
    }

    @Override
    public Query query(SQL sql) {
        return new QueryImpl(this, null, sql.sql(), sql.args(), false);
    }

    @Override
    public ExecuteResult execute(String sql, Object... params) {
        return new QueryImpl(this, null, sql, params, startsWithInsert(sql)).execute();
    }

    @Override
    public ExecuteResult execute(SQL sql) {
        return new QueryImpl(this, null, sql.sql(), sql.args(), sql.isInsert()).execute();
    }

    @Override
    public BatchQuery batch(String sql) {
        return new BatchQueryImpl(this, null, sql);
    }

    @Override
    public void transaction(Consumer<Transaction> work) {
        transaction(work, null);
    }

    @Override
    public void transaction(Consumer<Transaction> work, IsolationLevel isolation) {
        TransactionImpl tx = (TransactionImpl) beginTransaction(isolation);
        try {
            work.accept(tx);
            tx.commit();
        } catch (Throwable e) {
            tx.rollbackSilent();
            throw e;
        } finally {
            tx.closeConnection();
        }
    }

    @Override
    public Transaction beginTransaction() {
        return beginTransaction(null);
    }

    static boolean startsWithInsert(String sql) {
        if (sql == null) {
            return false;
        }
        int index = skipIgnorableSqlPrefix(sql);
        int end = index + "insert".length();
        return end <= sql.length()
            && sql.regionMatches(true, index, "insert", 0, "insert".length())
            && (end == sql.length() || !isIdentifierChar(sql.charAt(end)));
    }

    @Override
    public boolean ping() {
        try {
            PooledConnection conn = pool.borrow();
            try {
                return conn.jdbcConnection().isValid(5);
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

    @Override
    public Transaction beginTransaction(IsolationLevel isolation) {
        PooledConnection conn = pool.borrow();
        int originalIsolation = -1;
        try {
            originalIsolation = conn.jdbcConnection().getTransactionIsolation();
            conn.jdbcConnection().setAutoCommit(false);
            TransactionImpl tx = new TransactionImpl(this, conn, originalIsolation);
            if (isolation != null && isolation != IsolationLevel.DEFAULT) {
                conn.jdbcConnection().setTransactionIsolation(isolation.jdbcLevel());
            }
            return tx;
        } catch (SQLException e) {
            try {
                if (originalIsolation >= 0) {
                    conn.jdbcConnection().setTransactionIsolation(originalIsolation);
                }
                conn.jdbcConnection().setAutoCommit(true);
            } catch (SQLException ex) {
                LOG.trace("Error restoring autoCommit after transaction begin failure", ex);
            }
            pool.release(conn);
            throw new SqlException("Failed to begin transaction", e);
        }
    }

    private static final class TransactionImpl implements Transaction {
        private final DatabaseImpl db;
        private final PooledConnection conn;
        private final int originalIsolation;
        private boolean completed;
        private boolean released;

        private TransactionImpl(DatabaseImpl db, PooledConnection conn, int originalIsolation) {
            this.db = db;
            this.conn = conn;
            this.originalIsolation = originalIsolation;
        }

        @Override
        public Transaction isolation(IsolationLevel level) {
            if (completed) {
                throw new SqlException("Transaction is already finished");
            }
            IsolationLevel next = level == null ? IsolationLevel.DEFAULT : level;
            try {
                conn.jdbcConnection().setTransactionIsolation(
                    next == IsolationLevel.DEFAULT ? originalIsolation : next.jdbcLevel()
                );
                return this;
            } catch (SQLException e) {
                throw new SqlException("Failed to set isolation level", e);
            }
        }

        @Override
        public Query query(String sql, Object... params) {
            if (completed) {
                throw new SqlException("Transaction is already finished");
            }
            return new QueryImpl(db, conn, sql, params, false);
        }

        @Override
        public Query query(SQL sql) {
            if (completed) {
                throw new SqlException("Transaction is already finished");
            }
            return new QueryImpl(db, conn, sql.sql(), sql.args(), false);
        }

        @Override
        public ExecuteResult execute(String sql, Object... params) {
            if (completed) {
                throw new SqlException("Transaction is already finished");
            }
            return new QueryImpl(db, conn, sql, params, startsWithInsert(sql)).execute();
        }

        @Override
        public ExecuteResult execute(SQL sql) {
            if (completed) {
                throw new SqlException("Transaction is already finished");
            }
            return new QueryImpl(db, conn, sql.sql(), sql.args(), sql.isInsert()).execute();
        }

        @Override
        public BatchQuery batch(String sql) {
            if (completed) {
                throw new SqlException("Transaction is already finished");
            }
            return new BatchQueryImpl(db, conn, sql);
        }

        @Override
        public void commit() {
            if (completed) {
                return;
            }
            try {
                conn.jdbcConnection().commit();
                completed = true;
                restoreConnectionState();
            } catch (SQLException e) {
                throw new SqlException("Commit failed", e);
            }
        }

        @Override
        public void rollback() {
            rollbackSilent();
        }

        void rollbackSilent() {
            if (completed) {
                return;
            }
            completed = true;
            try {
                conn.jdbcConnection().rollback();
                restoreConnectionState();
            } catch (SQLException e) {
                LOG.warn("Transaction rollback failed", e);
            }
        }

        @Override
        public void close() {
            if (!completed) {
                rollbackSilent();
            }
            closeConnection();
        }

        void closeConnection() {
            if (released) {
                return;
            }
            released = true;
            try {
                restoreConnectionState();
            } catch (SQLException e) {
                LOG.trace("Error restoring connection state on close", e);
            }
            db.pool.release(conn);
        }

        private void restoreConnectionState() throws SQLException {
            if (originalIsolation >= 0
                && conn.jdbcConnection().getTransactionIsolation() != originalIsolation) {
                conn.jdbcConnection().setTransactionIsolation(originalIsolation);
            }
            if (!conn.jdbcConnection().getAutoCommit()) {
                conn.jdbcConnection().setAutoCommit(true);
            }
        }
    }

    private static int skipIgnorableSqlPrefix(String sql) {
        int index = 0;
        while (index < sql.length()) {
            char c = sql.charAt(index);
            if (Character.isWhitespace(c)) {
                index++;
                continue;
            }
            if (c == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                index += 2;
                while (index < sql.length() && sql.charAt(index) != '\n') {
                    index++;
                }
                continue;
            }
            if (c == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
                index += 2;
                while (index < sql.length()) {
                    char bc = sql.charAt(index);
                    index++;
                    if (bc == '*' && index < sql.length() && sql.charAt(index) == '/') {
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
