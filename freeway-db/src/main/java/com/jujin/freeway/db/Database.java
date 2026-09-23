package com.jujin.freeway.db;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.db.dialect.Dialect;
import com.jujin.freeway.db.internal.DatabaseImpl;
import com.jujin.freeway.db.internal.RowMapperResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Primary data-access interface. Each {@code Database} wraps a JDBC connection
 * pool and provides query, execute, batch, and transaction methods.
 *
 * <p>Usage:
 * <pre>{@code
 * var db = Database.create(config);
 * List<User> users = db.query("SELECT * FROM users WHERE active = ?", true).list(User.class);
 * db.transaction(() -> {
 *     db.execute("UPDATE users SET name = ? WHERE id = ?", name, id);
 * });
 * }</pre>
 *
 * @see PoolConfig
 */
public interface Database extends AutoCloseable {

    // ====================== assembly ======================

    /**
     * Assembles a standalone {@code Database} — the one derivation point for
     * callers without a container (the container face is {@code DbModule}).
     * Standard parts: a fresh coercer carrying the JDBC coercion rules, the
     * dialect detected from the config's URL ({@link Dialect#of(String)}),
     * the standard pool, and no custom row mappers. Override any part
     * through {@link Wiring}.
     *
     * <pre>{@code
     * Database db = Database.create(PoolConfig.defaults(url, user, pass));
     * }</pre>
     */
    static Database create(PoolConfig config) {
        return create(Wiring.defaults(config));
    }

    /**
     * The full assembly. Coercion rules land exactly as on the container
     * path: a caller-supplied {@link CoercerDefault} receives any missing
     * JDBC rules but keeps its own rules' priority; the dialect is
     * {@code wiring.dialect()} when given and URL-detected otherwise. An
     * unknown URL scheme fails here — before any connection is opened —
     * with guidance naming the fix.
     */
    static Database create(Wiring wiring) {
        Objects.requireNonNull(wiring, "wiring");
        PoolConfig config = wiring.config();
        Coercer effective = wiring.coercer();
        if (effective == null) {
            effective = Coercions.jdbcCoercer();
        } else if (effective instanceof CoercerDefault cd) {
            // A custom CoercerDefault must not silently lose the JDBC rules
            // (Date/Timestamp/Time → java.time) that the IoC path
            // always contributes. Caller-registered rules keep priority.
            for (var rule : Coercions.jdbcDefaults()) {
                cd.registerIfAbsent(rule);
            }
        }
        Dialect dialect = wiring.dialect() != null
            ? wiring.dialect()
            : Dialect.of(config.url());
        return new DatabaseImpl(
            config,
            new RowMapperResolver(effective, wiring.rowMappers(), Map.of()),
            wiring.pool(),
            dialect
        );
    }

    /**
     * Parts handed to {@link #create(Wiring)}: {@code config} is required,
     * the rest are {@code null} unless overridden — a {@code null} means
     * "apply the standard assembly's default", and the defaults themselves
     * live with {@code create} (and {@code DbModule}), never re-stated here.
     */
    record Wiring(
        PoolConfig config,
        Coercer coercer,
        Pool pool,
        Dialect dialect,
        Map<Class<?>, RowMapper<?>> rowMappers
    ) {
        public Wiring {
            Objects.requireNonNull(config, "config");
            rowMappers = rowMappers == null ? Map.of() : Map.copyOf(rowMappers);
        }

        /** The required part only; every other part takes its standard default. */
        public static Wiring defaults(PoolConfig config) {
            return new Wiring(config, null, null, null, null);
        }

        /** Custom coercer; missing JDBC rules are still added (see {@code create}). */
        public Wiring withCoercer(Coercer value) {
            return new Wiring(config, value, pool, dialect, rowMappers);
        }

        /** Bring-your-own pool (e.g. an adapter pool); {@code null} → standard pool. */
        public Wiring withPool(Pool value) {
            return new Wiring(config, coercer, value, dialect, rowMappers);
        }

        /** Explicit dialect — wins over URL detection and makes any URL scheme usable. */
        public Wiring withDialect(Dialect value) {
            return new Wiring(config, coercer, pool, value, rowMappers);
        }

        /** Adds a per-type row mapper; duplicate registration for a type fails. */
        public <T> Wiring withRowMapper(Class<T> type, RowMapper<? extends T> mapper) {
            Class<T> t = Objects.requireNonNull(type, "type");
            RowMapper<?> m = Objects.requireNonNull(mapper, "mapper");
            if (rowMappers.containsKey(t)) {
                throw new IllegalStateException(
                    "Duplicate row mapper registration for " + t.getName()
                );
            }
            Map<Class<?>, RowMapper<?>> next = new LinkedHashMap<>(rowMappers);
            next.put(t, m);
            return new Wiring(config, coercer, pool, dialect, next);
        }
    }

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
     * instances (e.g. obtained from a {@link DatabaseRegistry}) commits
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
     * instances (e.g. via {@link DatabaseRegistry}) commits independently and is
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
