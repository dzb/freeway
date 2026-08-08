package com.jujin.freeway.db;

import com.jujin.freeway.commons.coercion.CoerceRule;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.config.ConfigSpec;
import com.jujin.freeway.db.internal.DatabaseHubImpl;
import com.jujin.freeway.db.internal.DatabaseImpl;
import com.jujin.freeway.db.internal.PoolDefault;
import com.jujin.freeway.db.internal.RowMapperResolver;
import com.jujin.freeway.db.migration.MigrationRunner;
import com.jujin.freeway.db.dialect.Dialect;
import com.jujin.freeway.db.dialect.H2Dialect;
import com.jujin.freeway.db.dialect.MySqlDialect;
import com.jujin.freeway.db.dialect.PostgresDialect;
import com.jujin.freeway.db.schema.Schema;
import com.jujin.freeway.db.schema.SchemaEntity;
import com.jujin.freeway.db.dialect.SqliteDialect;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

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
        binder.bind(Dialect.class).to(H2Dialect.class).id("h2");

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
            .to(container -> {
                // Auto-register the single configured Database as "primary"
                // unless a user contribution already owns that name — the
                // user's database wins.
                List<NamedDatabase> named =
                    new ArrayList<>(container.extension(NamedDatabase.class).all());
                boolean userPrimary = named.stream()
                    .anyMatch(entry -> "primary".equals(entry.name()));
                if (!userPrimary) {
                    named.add(new NamedDatabase(
                        "primary",
                        container.get(Database.class)
                    ));
                }
                return new DatabaseHubImpl(named);
            });
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

    private static final ConfigSpec<String> URL =
        ConfigSpec.required(DbConfigKeys.URL, String.class, Function.identity());
    private static final ConfigSpec<String> USERNAME =
        ConfigSpec.required(DbConfigKeys.USERNAME, String.class, Function.identity());
    private static final ConfigSpec<Integer> POOL_MAX_SIZE =
        ConfigSpec.of(DbConfigKeys.POOL_MAX_SIZE, Integer.class,
            PoolConfig.DEFAULT_MAX_SIZE, Integer::parseInt);
    private static final ConfigSpec<Integer> POOL_MIN_IDLE =
        ConfigSpec.of(DbConfigKeys.POOL_MIN_IDLE, Integer.class,
            PoolConfig.DEFAULT_MIN_IDLE, Integer::parseInt);
    // Duration keys: no per-key parser — resolved by the container Coercer
    // ("2s" syntax, user-registered rules) via parse(raw, coercer).
    private static final ConfigSpec<Duration> POOL_CONNECTION_TIMEOUT =
        ConfigSpec.of(DbConfigKeys.POOL_CONNECTION_TIMEOUT, Duration.class,
            PoolConfig.DEFAULT_CONNECTION_TIMEOUT);
    private static final ConfigSpec<Duration> POOL_MAX_LIFETIME =
        ConfigSpec.of(DbConfigKeys.POOL_MAX_LIFETIME, Duration.class,
            PoolConfig.DEFAULT_MAX_LIFETIME);
    private static final ConfigSpec<Duration> POOL_MAX_IDLE_TIME =
        ConfigSpec.of(DbConfigKeys.POOL_MAX_IDLE_TIME, Duration.class,
            PoolConfig.DEFAULT_MAX_IDLE_TIME);
    private static final ConfigSpec<Duration> POOL_CLEAN_INTERVAL =
        ConfigSpec.of(DbConfigKeys.POOL_CLEAN_INTERVAL, Duration.class,
            PoolConfig.DEFAULT_CLEAN_INTERVAL);
    private static final ConfigSpec<Duration> POOL_HEALTH_CHECK_TIMEOUT =
        ConfigSpec.of(DbConfigKeys.POOL_HEALTH_CHECK_TIMEOUT, Duration.class,
            PoolConfig.DEFAULT_HEALTH_CHECK_TIMEOUT);
    private static final ConfigSpec<Duration> QUERY_TIMEOUT =
        ConfigSpec.of(DbConfigKeys.QUERY_TIMEOUT, Duration.class,
            PoolConfig.DEFAULT_QUERY_TIMEOUT);

    private static PoolConfig buildConfig(Container container) {
        SymbolSource s = container.get(SymbolSource.class);
        Coercer coercer = container.get(Coercer.class);
        return new PoolConfig(
            URL.parse(s.resolve(DbConfigKeys.URL, null)),
            USERNAME.parse(s.resolve(DbConfigKeys.USERNAME, null)),
            s.resolve(DbConfigKeys.PASSWORD, ""),
            POOL_MAX_SIZE.parse(s.resolve(DbConfigKeys.POOL_MAX_SIZE, "")),
            POOL_MIN_IDLE.parse(s.resolve(DbConfigKeys.POOL_MIN_IDLE, "")),
            POOL_CONNECTION_TIMEOUT.parse(s.resolve(DbConfigKeys.POOL_CONNECTION_TIMEOUT, ""), coercer),
            POOL_MAX_LIFETIME.parse(s.resolve(DbConfigKeys.POOL_MAX_LIFETIME, ""), coercer),
            POOL_MAX_IDLE_TIME.parse(s.resolve(DbConfigKeys.POOL_MAX_IDLE_TIME, ""), coercer),
            POOL_CLEAN_INTERVAL.parse(s.resolve(DbConfigKeys.POOL_CLEAN_INTERVAL, ""), coercer),
            s.resolve(DbConfigKeys.POOL_HEALTH_CHECK_QUERY, null),
            POOL_HEALTH_CHECK_TIMEOUT.parse(s.resolve(DbConfigKeys.POOL_HEALTH_CHECK_TIMEOUT, ""), coercer),
            QUERY_TIMEOUT.parse(s.resolve(DbConfigKeys.QUERY_TIMEOUT, ""), coercer)
        );
    }

    private static Database buildDatabase(Container container) {
        PoolConfig config = container.get(PoolConfig.class);
        RowMapperResolver resolver = container.get(RowMapperResolver.class);
        Pool pool = container.get(Pool.class);
        Dialect dialect = resolveDialect(container);
        return new DatabaseImpl(config, resolver, pool, dialect);
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
        int total = 0;
        for (SchemaEntity se : entities) {
            if (se.entityTypes().length == 0) continue;

            if (!enabledGroups.isEmpty() && !enabledGroups.contains(se.name())) {
                LOG.debug("Schema group '{}' skipped (not in {})", se.name(), DbConfigKeys.SCHEMA_GROUPS);
                continue;
            }

            // The schema dialect always comes from the database.
            int ops = Schema.ensure(db, se.entityTypes());
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
        return DatabaseBuilder.dialectForUrl(url).dialectId();
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
