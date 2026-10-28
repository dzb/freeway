package com.jujin.freeway.db.internal;

import com.jujin.freeway.db.IsolationLevel;
import com.jujin.freeway.db.PooledConnection;
import com.jujin.freeway.db.SqlException;

import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A connection this call owns, running one transaction: borrow → autoCommit
 * off (and isolation, when asked) → commit or rollback → restore → release.
 *
 * <p>Two call sites used to spell this lifecycle twice — {@code
 * DatabaseImpl.transaction} and {@code BatchQueryImpl.execute} — including
 * the "cannot restore autoCommit means destroy the connection" guard. That
 * guard is the reason the duplication mattered: a copy that forgets it hands
 * an {@code autoCommit=false} connection back to the pool, and every later
 * borrower's statements sit in a transaction nobody commits.</p>
 *
 * <p>The close step restores the connection state and releases the handle
 * exactly once, on every path. {@link #commit()} and {@link
 * #rollbackQuietly()} only settle the transaction; closing is unconditional,
 * which is why a throwing work block cannot leak the connection.</p>
 */
final class OwnedTransaction implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(OwnedTransaction.class);

    private final DatabaseImpl database;
    private final PooledConnection connection;
    private final int originalIsolation;
    private boolean closed;

    private OwnedTransaction(DatabaseImpl database, PooledConnection connection,
            int originalIsolation) {
        this.database = database;
        this.connection = connection;
        this.originalIsolation = originalIsolation;
    }

    /**
     * Borrows a connection and starts its transaction, optionally at
     * {@code isolation} ({@code null}/DEFAULT keeps the connection's own).
     * A failure here releases the handle before throwing — the caller never
     * receives a half-started transaction.
     */
    static OwnedTransaction begin(DatabaseImpl database, IsolationLevel isolation) {
        PooledConnection connection = database.pool().borrow();
        int originalIsolation = -1;
        try {
            var raw = connection.connection();
            originalIsolation = raw.getTransactionIsolation();
            raw.setAutoCommit(false);
            if (isolation != null && isolation != IsolationLevel.DEFAULT) {
                raw.setTransactionIsolation(isolation.jdbcLevel());
            }
            return new OwnedTransaction(database, connection, originalIsolation);
        } catch (SQLException e) {
            restoreAndRelease(database, connection, originalIsolation);
            throw new SqlException("Unable to start a transaction: " + e.getMessage(), e);
        }
    }

    PooledConnection connection() {
        return connection;
    }

    /** Commits the transaction. Throws so the caller can name its own step. */
    void commit() throws SQLException {
        connection.connection().commit();
    }

    /** Rolls back and swallows a rollback failure — the caller is unwinding. */
    void rollbackQuietly() {
        try {
            connection.connection().rollback();
        } catch (SQLException e) {
            LOG.warn("Transaction rollback failed", e);
        }
    }

    /** Restores the connection state and releases the handle; idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        restoreAndRelease(database, connection, originalIsolation);
    }

    /**
     * The one restore rule: put isolation and autoCommit back, and when that
     * fails destroy the physical connection instead of recycling it — an
     * {@code autoCommit=false} connection in the pool strands every later
     * borrower.
     */
    private static void restoreAndRelease(DatabaseImpl database,
            PooledConnection connection, int originalIsolation) {
        try {
            var raw = connection.connection();
            if (originalIsolation >= 0
                    && raw.getTransactionIsolation() != originalIsolation) {
                raw.setTransactionIsolation(originalIsolation);
            }
            if (!raw.getAutoCommit()) {
                raw.setAutoCommit(true);
            }
        } catch (SQLException e) {
            LOG.warn("Failed to restore connection state — destroying connection", e);
            database.pool().invalidate(connection);
        }
        database.pool().release(connection);
    }
}
