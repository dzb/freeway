package com.jujin.freeway.db;

import com.jujin.freeway.db.dialect.Dialect;

/**
 * Primary data-access interface. Each {@code Database} wraps a JDBC connection
 * pool and provides query, execute, batch, and transaction methods.
 *
 * <p>Usage:
 * <pre>{@code
 * var db = DatabaseBuilder.from(config).build();
 * List<User> users = db.query("SELECT * FROM users WHERE active = ?", true).list(User.class);
 * db.transaction(() -> {
 *     db.execute("UPDATE users SET name = ? WHERE id = ?", name, id);
 * });
 * }</pre>
 *
 * @see DatabaseBuilder
 * @see PoolConfig
 */
public interface Database extends AutoCloseable {

    /**
     * Returns the SQL dialect associated with this database.
     */
    Dialect dialect();

    /**
     * Creates a new {@link Query} with positional or named parameters.
     *
     * @param sql    the SQL string with {@code ?}, {@code :name}, or {@code $name} placeholders
     * @param params the parameter values
     * @return a Query terminal (call {@code list()}, {@code one()}, etc.)
     */
    Query query(String sql, Object... params);

    /**
     * Convenience method accepting a {@link Sql} builder.
     *
     * <p>The built SQL is validated against this database's dialect before
     * execution — SQL using features the dialect does not support (e.g.
     * {@code RETURNING} or {@code ON CONFLICT} on MySQL) fails with a
     * {@link SqlException} instead of being sent to the database as-is.
     *
     * @see #query(String, Object...)
     */
    default Query query(Sql sql) {
        return query(sql.sql(dialect()), sql.args());
    }

    /**
     * Executes an INSERT, UPDATE, or DELETE statement.
     *
     * @param sql    the SQL string with placeholders
     * @param params the parameter values
     * @return the execution result (affected rows and generated key)
     */
    ExecuteResult execute(String sql, Object... params);

    /**
     * Convenience method accepting a {@link Sql} builder.
     *
     * <p>The built SQL is validated against this database's dialect before
     * execution — SQL using features the dialect does not support (e.g.
     * {@code RETURNING} or {@code ON CONFLICT} on MySQL) fails with a
     * {@link SqlException} instead of being sent to the database as-is.
     *
     * <p><b>{@code INSERT ... RETURNING} (and {@code UPDATE/DELETE ...
     * RETURNING}) must be consumed via {@link #query(Sql)}</b>, not here:
     * RETURNING produces rows, and {@code execute} discards them — it only
     * reports affected rows and a single generated key. Executing a
     * RETURNING statement through {@code execute} silently drops the returned
     * column values.
     *
     * @see #execute(String, Object...)
     */
    default ExecuteResult execute(Sql sql) {
        return execute(sql.sql(dialect()), sql.args());
    }

    /**
     * Returns a batch executor for the given SQL template.
     *
     * @param sql the SQL template with placeholders
     * @return a {@link BatchQuery} for adding row batches and executing
     */
    BatchQuery batch(String sql);

    /**
     * Truncates (or deletes all rows from) a table using dialect-appropriate syntax.
     * SQLite uses {@code DELETE FROM} since it has no TRUNCATE.
     */
    default void truncate(String tableName) {
        execute(dialect().truncateTable(tableName));
    }

    /**
     * Runs the given work inside a transaction. The transaction is committed
     * on success and rolled back on exception.
     *
     * <p><b>The transaction covers only this {@code Database}'s
     * connection.</b> Work executed on <em>other</em> {@code Database}
     * instances (e.g. obtained from a {@link DatabaseHub}) commits
     * independently and is not rolled back when this transaction fails — do
     * not mix multi-database writes inside a single-database transaction
     * expecting atomicity.
     *
     * @param work the transactional code
     */
    void transaction(Transactional work);

    /**
     * Returns whether the calling thread is currently inside a transaction on
     * this database.
     *
     * <p>Used by guards that must refuse work which would silently break
     * transaction semantics — e.g. DDL on a database without transactional
     * DDL (MySQL/MariaDB), where every DDL statement implicitly commits the
     * surrounding transaction. Implementations without thread-bound
     * transactions return {@code false}.
     */
    default boolean inTransaction() {
        return false;
    }

    /**
     * Runs the given work inside a transaction with the specified isolation level.
     *
     * <p>As with {@link #transaction(Transactional)}, the transaction covers
     * only this {@code Database}'s connection: work on other {@code Database}
     * instances (e.g. via {@link DatabaseHub}) commits independently and is
     * not rolled back with this transaction.
     *
     * @param isolation the isolation level (use {@link IsolationLevel#DEFAULT} to skip setting)
     * @param work      the transactional code
     */
    void transaction(IsolationLevel isolation, Transactional work);

    /**
     * Checks whether the database is reachable by executing a lightweight query.
     *
     * @return true if the connection pool has at least one working connection
     */
    boolean ping();

    /**
     * Returns connection-pool statistics.
     */
    DatabaseStats stats();

    @Override
    void close();
}
