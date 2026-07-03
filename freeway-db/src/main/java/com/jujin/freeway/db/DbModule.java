package com.jujin.freeway.db;

import com.jujin.freeway.commons.coercion.CoerceRule;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.db.internal.DatabaseHubImpl;
import com.jujin.freeway.db.internal.DatabaseImpl;
import com.jujin.freeway.db.internal.PoolDefault;
import com.jujin.freeway.db.internal.RowMapperResolver;
import com.jujin.freeway.db.migration.MigrationRunner;
import com.jujin.freeway.db.schema.*;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * IoC module that integrates {@code freeway-db} with the Freeway container.
 *
 * <p>Installing this module provides:
 * <ul>
 *   <li>{@link Database} — created from {@link PoolConfig} resolved from config cascade</li>
 *   <li>{@link Orm} — bound as a singleton</li>
 *   <li>{@link DatabaseHub} — multi-datasource routing</li>
 *   <li>{@link Pool} — built-in; override via extension module with {@code .primary()}</li>
 *   <li>{@link Dialect} — auto-detected from JDBC URL or overridden via {@link DbConfigKeys#DIALECT}</li>
 *   <li>{@link MigrationRunner} — versioned SQL migration at startup</li>
 *   <li>RuntimeHook that runs Schema auto-DDL and migrations before the HTTP server starts</li>
 * </ul>
 */
@Marker(Builtin.class)
public final class DbModule implements ModuleEx {

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
        Coercer coercer = container.get(Coercer.class);
        return new PoolConfig(
            Objects.requireNonNull(s.resolve(DbConfigKeys.URL), DbConfigKeys.URL + " is required"),
            Objects.requireNonNull(s.resolve(DbConfigKeys.USERNAME), DbConfigKeys.USERNAME + " is required"),
            s.resolve(DbConfigKeys.PASSWORD, ""),
            Integer.parseInt(s.resolve(DbConfigKeys.POOL_MAX_SIZE, String.valueOf(PoolConfig.DEFAULT_MAX_SIZE))),
            Integer.parseInt(s.resolve(DbConfigKeys.POOL_MIN_IDLE, String.valueOf(PoolConfig.DEFAULT_MIN_IDLE))),
            resolveDuration(coercer, s, DbConfigKeys.POOL_CONNECTION_TIMEOUT, PoolConfig.DEFAULT_CONNECTION_TIMEOUT),
            resolveDuration(coercer, s, DbConfigKeys.POOL_MAX_LIFETIME, PoolConfig.DEFAULT_MAX_LIFETIME),
            resolveDuration(coercer, s, DbConfigKeys.POOL_MAX_IDLE_TIME, PoolConfig.DEFAULT_MAX_IDLE_TIME),
            resolveDuration(coercer, s, DbConfigKeys.POOL_CLEAN_INTERVAL, PoolConfig.DEFAULT_CLEAN_INTERVAL),
            s.resolve(DbConfigKeys.POOL_HEALTH_CHECK_QUERY, null),
            resolveDuration(coercer, s, DbConfigKeys.POOL_HEALTH_CHECK_TIMEOUT, PoolConfig.DEFAULT_HEALTH_CHECK_TIMEOUT),
            resolveDuration(coercer, s, DbConfigKeys.QUERY_TIMEOUT, PoolConfig.DEFAULT_QUERY_TIMEOUT)
        );
    }

    private static Duration resolveDuration(Coercer coercer, SymbolSource s, String key, Duration def) {
        String raw = s.resolve(key, "");
        return raw.isBlank() ? def : coercer.coerce(raw, Duration.class);
    }

    private static Database buildDatabase(Container container) {
        PoolConfig config = container.get(PoolConfig.class);
        RowMapperResolver resolver = container.get(RowMapperResolver.class);
        Pool pool = container.get(Pool.class);
        return new DatabaseImpl(config, resolver, pool);
    }

    private static MigrationRunner buildMigrationRunner(Container container) {
        SymbolSource s = container.get(SymbolSource.class);
        Coercer coercer = container.get(Coercer.class);
        return new MigrationRunner(
            container.get(Database.class),
            coercer.coerce(s.resolve(DbConfigKeys.MIGRATION_ENABLED, "true"), boolean.class),
            s.resolve(DbConfigKeys.MIGRATION_PATH, "db/migration/"),
            s.resolve(DbConfigKeys.MIGRATION_TABLE, "_migrations")
        );
    }

    private static void runSchema(Container container) {
        SymbolSource s = container.get(SymbolSource.class);
        Coercer coercer = container.get(Coercer.class);
        if (!coercer.coerce(s.resolve(DbConfigKeys.SCHEMA_AUTO, "true"), boolean.class)) {
            return;
        }
        var entities = container.extension(SchemaEntity.class).all();
        if (entities.isEmpty()) {
            return;
        }

        Set<String> enabledGroups = parseGroupFilter(
            s.resolve(DbConfigKeys.SCHEMA_GROUPS, "")
        );

        Database db = container.get(Database.class);
        Dialect globalDialect = resolveDialect(container);
        int total = 0;
        for (SchemaEntity se : entities) {
            if (se.entityTypes().length == 0) continue;

            if (!enabledGroups.isEmpty() && !enabledGroups.contains(se.name())) {
                LOG.debug("Schema group '{}' skipped (not in {})", se.name(), DbConfigKeys.SCHEMA_GROUPS);
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
        String configured = s.resolve(DbConfigKeys.DIALECT, "");
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
        String url = s.resolve(DbConfigKeys.URL, "");
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
