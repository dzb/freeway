package com.jujin.freeway.db;

import com.jujin.freeway.commons.coercion.CoerceRule;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.db.internal.DatabaseHubImpl;
import com.jujin.freeway.db.internal.DatabaseImpl;
import com.jujin.freeway.db.internal.PoolDefault;
import com.jujin.freeway.db.internal.RowMapperResolver;
import com.jujin.freeway.db.migration.MigrationRunner;
import com.jujin.freeway.db.schema.Dialect;
import com.jujin.freeway.db.schema.MySqlDialect;
import com.jujin.freeway.db.schema.PostgresDialect;
import com.jujin.freeway.db.schema.Schema;
import com.jujin.freeway.db.schema.SqliteDialect;
import com.jujin.freeway.db.schema.SchemaEntity;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Module2;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DbModule implements Module2{

    private static final Logger LOG = LoggerFactory.getLogger(DbModule.class);

    @Override
    public void bind(Binder binder) {
        // config
        binder
            .bind(PoolConfig.class)
            .to(container -> buildConfig(container));
        binder
            .bind(Pool.class)
            .to(container -> {
                PoolConfig config = container.get(PoolConfig.class);
                return new PoolDefault(config);
            })
            .id("builtin");

        // dialect — config-driven, url-detected, default Postgres
        binder
            .bind(Dialect.class)
            .to(PostgresDialect.class)
            .id("postgresql")
            .primary();
        binder.bind(Dialect.class).to(MySqlDialect.class).id("mysql");
        binder.bind(Dialect.class).to(SqliteDialect.class).id("sqlite");

        // database
        binder.bind(RowMapperResolver.class).to(container ->
            new RowMapperResolver(
                container.get(Coercer.class),
                container.extension(RowMapping.class).all()
            )
        );
        binder
            .bind(Database.class)
            .to(container -> buildDatabase(container));
        binder
            .bind(DatabaseHub.class)
            .to(container ->
                new DatabaseHubImpl(container.extension(DatabaseNamed.class).all())
            );
        binder.bind(Orm.class).to(Orm.class);
        binder
            .bind(MigrationRunner.class)
            .to(container -> buildMigrationRunner(container));

        // coercion
        binder
            .contribute(CoerceRule.class)
            .add(
                new CoerceRule<>(
                    String.class,
                    Duration.class,
                    DbModule::parseDuration
                )
            );
            for (CoerceRule<?, ?> rule : Coercions.jdbcDefaults()) {
                binder.contribute(CoerceRule.class).add(rule);
            }

            // lifecycle: Schema (auto-DDL) → Migration (SQL evolution)
            binder
                .contribute(RuntimeHook.class)
                .add("freeway.db.migration", new RuntimeHook() {
                    @Override
                    public void start(Container container) {
                        runSchema(container);
                        runMigration(container);
                    }
                })
                .before("freeway.http.server");
        }

    private static PoolConfig buildConfig(Container container) {
        SymbolSource s = container.get(SymbolSource.class);
        return new PoolConfig(
            Objects.requireNonNull(s.resolve("freeway.db.url"), "freeway.db.url is required"),
            Objects.requireNonNull(s.resolve("freeway.db.username"), "freeway.db.username is required"),
            s.resolve("freeway.db.password", ""),
            Integer.parseInt(s.resolve("freeway.db.pool.max-size", String.valueOf(PoolConfig.DEFAULT_MAX_SIZE))),
            Integer.parseInt(s.resolve("freeway.db.pool.min-idle", String.valueOf(PoolConfig.DEFAULT_MIN_IDLE))),
            resolveDuration(s, "freeway.db.pool.connection-timeout", PoolConfig.DEFAULT_CONNECTION_TIMEOUT),
            resolveDuration(s, "freeway.db.pool.max-lifetime", PoolConfig.DEFAULT_MAX_LIFETIME),
            resolveDuration(s, "freeway.db.pool.max-idle-time", PoolConfig.DEFAULT_MAX_IDLE_TIME),
            resolveDuration(s, "freeway.db.pool.clean-interval", PoolConfig.DEFAULT_CLEAN_INTERVAL),
            s.resolve("freeway.db.pool.health-check-query", null),
            resolveDuration(s, "freeway.db.pool.health-check-timeout", PoolConfig.DEFAULT_HEALTH_CHECK_TIMEOUT),
            resolveDuration(s, "freeway.db.query-timeout", PoolConfig.DEFAULT_QUERY_TIMEOUT)
        );
    }

    private static Duration resolveDuration(SymbolSource s, String key, Duration def) {
        String raw = s.resolve(key, "");
        return raw.isBlank() ? def : parseDuration(raw);
    }

    private static Database buildDatabase(Container container) {
        PoolConfig config = container.get(PoolConfig.class);
        RowMapperResolver resolver = container.get(RowMapperResolver.class);
        String poolId = container.get(SymbolSource.class)
                .resolve("freeway.db.pool", "builtin");
        return new DatabaseImpl(config, resolver, resolvePool(container, poolId));
    }

    private static Pool resolvePool(Container container, String poolId) {
        try {
            return container.get(Pool.class, poolId);
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                "Unable to resolve pool engine '" + poolId + "'", ex);
        }
    }

    private static MigrationRunner buildMigrationRunner(Container container) {
        SymbolSource s = container.get(SymbolSource.class);
        return new MigrationRunner(
            container.get(Database.class),
            parseBool(s.resolve("freeway.db.migration.enabled", "true")),
            s.resolve("freeway.db.migration.path", "db/migration/"),
            s.resolve("freeway.db.migration.table", "_migrations")
        );
    }

    private static boolean parseBool(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Boolean value must not be blank");
        }
        String v = value.trim();
        if ("true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v)
                || "on".equalsIgnoreCase(v) || "1".equals(v)) {
            return true;
        }
        if ("false".equalsIgnoreCase(v) || "no".equalsIgnoreCase(v)
                || "off".equalsIgnoreCase(v) || "0".equals(v)) {
            return false;
        }
        throw new IllegalArgumentException("Invalid boolean value: " + value);
    }

    static Duration parseDuration(String text) {
        String value = text.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Invalid duration: " + text);
        }
        try {
            if (value.endsWith("ms")) return Duration.ofMillis(
                Long.parseLong(value.substring(0, value.length() - 2).trim())
            );
            if (value.endsWith("s")) return Duration.ofSeconds(
                Long.parseLong(value.substring(0, value.length() - 1).trim())
            );
            if (value.endsWith("m")) return Duration.ofMinutes(
                Long.parseLong(value.substring(0, value.length() - 1).trim())
            );
            if (value.endsWith("h")) return Duration.ofHours(
                Long.parseLong(value.substring(0, value.length() - 1).trim())
            );
            return Duration.ofMillis(Long.parseLong(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid duration: " + text, e);
        }
    }

    private static void runSchema(Container container) {
        SymbolSource s = container.get(SymbolSource.class);
        if (!parseBool(s.resolve("freeway.db.schema.auto", "true"))) {
            return;
        }
        var entities = container.extension(SchemaEntity.class).all();
        if (entities.isEmpty()) {
            return;
        }

        Set<String> enabledGroups = parseGroupFilter(
            s.resolve("freeway.db.schema.groups", "")
        );

        Database db = container.get(Database.class);
        Dialect globalDialect = resolveDialect(container);
        int total = 0;
        for (SchemaEntity se : entities) {
            if (se.entityTypes().length == 0) continue;

            if (!enabledGroups.isEmpty() && !enabledGroups.contains(se.name())) {
                LOG.debug("Schema group '{}' skipped (not in freeway.db.schema.groups)", se.name());
                continue;
            }

            Dialect dialect = se.dialect() != null ? se.dialect() : globalDialect;
            int ops = Schema.ensure(db, dialect, se.entityTypes());
            if (ops > 0) {
                LOG.info("Schema group '{}' applied {} change(s)", se.name(), ops);
            }
            total += ops;
        }
        if (total > 0) {
            LOG.info("Schema auto-migration applied {} total change(s)", total);
        }
    }

    private static void runMigration(Container container) {
        MigrationRunner runner = container.get(MigrationRunner.class);
        int ran = runner.run();
        if (ran > 0) {
            LOG.info("SQL migrations applied: {} file(s)", ran);
        }
    }

    /**
     * Resolve the global dialect.
     * Order: {@code freeway.db.dialect} config → JDBC URL auto-detect →
     * default {@code PostgresDialect}.
     */
    static Dialect resolveDialect(Container container) {
        SymbolSource s = container.get(SymbolSource.class);
        String configured = s.resolve("freeway.db.dialect", "");
        boolean explicit = !configured.isBlank();
        String dialectId = explicit ? configured : detectDialect(s);
        if (!dialectId.isBlank()) {
            try {
                return container.get(Dialect.class, dialectId);
            } catch (RuntimeException ex) {
                if (explicit) {
                    throw new IllegalStateException(
                        "Unknown dialect '" + dialectId + "'", ex);
                }
                LOG.warn("Dialect '{}' not found, falling back to default", dialectId);
            }
        }
        return container.get(Dialect.class);
    }

    static String detectDialect(SymbolSource s) {
        String url = s.resolve("freeway.db.url", "");
        if (url.contains(":postgresql:")) return "postgresql";
        if (url.contains(":mysql:") || url.contains(":mariadb:")) return "mysql";
        if (url.contains(":h2:")) {
            return url.contains("MODE=MySQL") || url.contains("MODE=MariaDB")
                    ? "mysql" : "postgresql";
        }
        if (url.contains(":sqlite:")) return "sqlite";
        return "";
    }

    private static Set<String> parseGroupFilter(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> set = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                set.add(trimmed);
            }
        }
        return Set.copyOf(set);
    }
}
