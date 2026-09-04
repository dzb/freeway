package com.jujin.freeway.db;
import java.sql.Date;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerImpl;
import com.jujin.freeway.db.internal.DatabaseImpl;
import com.jujin.freeway.db.internal.RowMapperResolver;
import com.jujin.freeway.db.dialect.Dialect;
import com.jujin.freeway.db.dialect.H2Dialect;
import com.jujin.freeway.db.dialect.MySqlDialect;
import com.jujin.freeway.db.dialect.PostgresDialect;
import com.jujin.freeway.db.dialect.SqliteDialect;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Fluent builder for creating a {@link Database} instance.
 *
 * <p>Minimal example:
 * <pre>{@code
 * Database db = DatabaseBuilder.from(PoolConfig.defaults(url, user, pass)).build();
 * }</pre>
 *
 * <p>Custom pool, coercer, dialect, or row mappers can be configured before calling {@link #build()}:
 * <pre>{@code
 * Database db = DatabaseBuilder.from(config)
 *     .pool(myPool)
 *     .dialect(new MySqlDialect())
 *     .rowMapper(User.class, (rs, n) -> new User(rs.getLong("id"), rs.getString("name")))
 *     .build();
 * }</pre>
 *
 * @see Database
 * @see PoolConfig
 */
public final class DatabaseBuilder {

    private PoolConfig config;
    private Coercer coercer;
    private Pool pool;
    private Dialect dialect;
    private final Map<Class<?>, RowMapper<?>> rowMappers =
        new LinkedHashMap<>();

    public static DatabaseBuilder from(PoolConfig config) {
        return new DatabaseBuilder().config(
            Objects.requireNonNull(config, "config")
        );
    }

    public DatabaseBuilder config(PoolConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        return this;
    }

    public DatabaseBuilder coercer(Coercer coercer) {
        this.coercer = Objects.requireNonNull(coercer, "coercer");
        return this;
    }

    public DatabaseBuilder pool(Pool pool) {
        this.pool = Objects.requireNonNull(pool, "pool");
        return this;
    }

    public DatabaseBuilder dialect(Dialect dialect) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        return this;
    }

    public <T> DatabaseBuilder rowMapper(
        Class<T> type,
        RowMapper<? extends T> mapper
    ) {
        Class<T> t = Objects.requireNonNull(type, "type");
        RowMapper<?> m = Objects.requireNonNull(mapper, "mapper");
        if (rowMappers.containsKey(t)) {
            throw new IllegalStateException(
                "Duplicate row mapper registration for " + t.getName()
            );
        }
        rowMappers.put(t, m);
        return this;
    }

    /**
     * Detects the SQL dialect from a JDBC URL. A {@code null} or blank URL
     * (no database configured yet) defaults to {@link PostgresDialect}, but an
     * URL with an unrecognized scheme fails fast: silently falling back to
     * PostgreSQL for {@code jdbc:oracle:...}, {@code jdbc:sqlserver:...}, etc.
     * would generate PostgreSQL syntax (SELECT statements, {@code ON
     * CONFLICT}, {@code pg_indexes}) that the target database rejects — and
     * the IoC path would not even warn.
     *
     * <p>Single source of truth for URL-based dialect detection — shared by
     * {@link #build()} (standalone use) and {@link DbModule} (IoC use) so both
     * resolve the same dialect for the same URL.
     *
     * @param url JDBC URL; may be {@code null} or blank (treated as no URL →
     *            default PostgreSQL dialect)
     * @throws IllegalStateException when the URL scheme is not a supported
     *                               database — configure a dialect explicitly
     *                               via {@link #dialect(Dialect)} (standalone)
     *                               or the {@code freeway.db.dialect} config
     *                               key (IoC)
     */
    static Dialect dialectForUrl(String url) {
        if (url == null || url.isBlank()) {
            return new PostgresDialect();
        }
        String upper = url.toUpperCase();
        if (url.contains("jdbc:mysql") || url.contains("jdbc:mariadb")) {
            return new MySqlDialect();
        }
        if (url.contains("jdbc:sqlite")) {
            return new SqliteDialect();
        }
        if (url.contains("jdbc:h2")) {
            if (
                upper.contains("MODE=MYSQL") ||
                upper.contains("MODE=MARIADB")
            ) {
                return new MySqlDialect();
            }
            if (upper.contains("MODE=POSTGRESQL")) {
                return new PostgresDialect();
            }
            return new H2Dialect();
        }
        if (url.contains("jdbc:postgresql")) {
            return new PostgresDialect();
        }
        throw new IllegalStateException(
            "No SQL dialect for JDBC URL '" + url + "' — unsupported database. "
                + "Supported URL schemes: mysql, mariadb, sqlite, h2, postgresql. "
                + "Set the dialect explicitly via DatabaseBuilder.dialect(...) "
                + "or the freeway.db.dialect config key"
        );
    }

    public Database build() {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        Coercer effective = coercer;
        if (effective == null) {
            CoercerImpl cd = new CoercerImpl();
            for (var rule : Coercions.jdbcDefaults()) {
                cd.register(rule);
            }
            effective = cd;
        } else if (effective instanceof CoercerImpl cd) {
            // A custom CoercerImpl must not silently lose the JDBC rules
            // (Date/Timestamp/Time → java.time) that the IoC path
            // always contributes. Caller-registered rules keep priority.
            for (var rule : Coercions.jdbcDefaults()) {
                cd.registerIfAbsent(rule);
            }
        }
        Dialect effectiveDialect = dialect != null
            ? dialect
            : dialectForUrl(config.url());
        return new DatabaseImpl(
            config,
            new RowMapperResolver(
                effective,
                Map.copyOf(rowMappers),
                Map.of()
            ),
            pool,
            effectiveDialect
        );
    }
}
