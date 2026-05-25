package com.jujin.freeway.db.internal;

import com.jujin.freeway.db.BatchQuery;
import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.DatabaseConfig;
import com.jujin.freeway.db.DatabaseStats;
import com.jujin.freeway.db.Query;
import com.jujin.freeway.db.SqlException;
import com.jujin.freeway.db.Transaction;
import java.sql.SQLException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DatabaseImpl implements Database {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseImpl.class);

    private final ConnectionPool pool;
    private final RowMapperRegistry rowMappers;
    private final int queryTimeoutSeconds;

    public DatabaseImpl(
        DatabaseConfig config,
        RowMapperRegistry rowMappers
    ) {
        this.pool = new ConnectionPool(PoolConfig.from(config));
        this.rowMappers = rowMappers;
        long millis = config.queryTimeout().toMillis();
        this.queryTimeoutSeconds = (int) Math.max(1, (millis + 999) / 1000);
    }

    @Override
    public Query sql(String sql, Object... params) {
        return new QueryImpl(this, null, sql, params);
    }

    @Override
    public BatchQuery batch(String sql) {
        return new BatchQueryImpl(this, null, sql);
    }

    @Override
    public void transaction(Consumer<Transaction> work) {
        TransactionImpl tx = (TransactionImpl) beginTransaction();
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
        PooledConnection conn = pool.borrow();
        try {
            conn.jdbcConnection().setAutoCommit(false);
            return new TransactionImpl(this, conn);
        } catch (SQLException e) {
            pool.release(conn);
            throw new SqlException("Failed to begin transaction", e);
        }
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

    RowMapperRegistry rowMappers() {
        return rowMappers;
    }

    ConnectionPool pool() {
        return pool;
    }

    private static final class TransactionImpl implements Transaction {
        private final DatabaseImpl db;
        private final PooledConnection conn;
        private boolean finished;

        private TransactionImpl(DatabaseImpl db, PooledConnection conn) {
            this.db = db;
            this.conn = conn;
        }

        @Override
        public Query sql(String sql, Object... params) {
            if (finished) {
                throw new SqlException("Transaction is already finished");
            }
            return new QueryImpl(db, conn, sql, params);
        }

        @Override
        public BatchQuery batch(String sql) {
            if (finished) {
                throw new SqlException("Transaction is already finished");
            }
            return new BatchQueryImpl(db, conn, sql);
        }

        @Override
        public void commit() {
            if (finished) {
                return;
            }
            finished = true;
            try {
                conn.jdbcConnection().commit();
                conn.jdbcConnection().setAutoCommit(true);
            } catch (SQLException e) {
                throw new SqlException("Commit failed", e);
            }
        }

        @Override
        public void rollback() {
            if (finished) {
                return;
            }
            finished = true;
            rollbackSilent();
        }

        void rollbackSilent() {
            try {
                conn.jdbcConnection().rollback();
                conn.jdbcConnection().setAutoCommit(true);
            } catch (SQLException e) {
                logger.warn("Transaction rollback failed", e);
            }
        }

        @Override
        public void close() {
            if (!finished) {
                rollbackSilent();
            }
            closeConnection();
        }

        void closeConnection() {
            try {
                if (!conn.jdbcConnection().getAutoCommit()) {
                    conn.jdbcConnection().setAutoCommit(true);
                }
            } catch (SQLException e) {
                logger.trace("Error restoring autoCommit on close", e);
            }
            db.pool.release(conn);
        }
    }
}
