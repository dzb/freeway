package com.jujin.freeway.db;

import com.jujin.freeway.db.schema.Dialect;

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
     * Convenience method accepting a {@link SQL} builder.
     *
     * @see #query(String, Object...)
     */
    default Query query(SQL sql) {
        return query(sql.sql(), sql.args());
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
     * Convenience method accepting a {@link SQL} builder.
     *
     * @see #execute(String, Object...)
     */
    default ExecuteResult execute(SQL sql) {
        return execute(sql.sql(), sql.args());
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
     * @param work the transactional code
     */
    void transaction(Transactional work);

    /**
     * Runs the given work inside a transaction with the specified isolation level.
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
