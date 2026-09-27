package com.jujin.freeway.db;

import com.jujin.freeway.commons.coercion.CoerceRule;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.ioc.symbol.KnownKeys;
import com.jujin.freeway.ioc.symbol.SymbolSpec;
import com.jujin.freeway.db.internal.DatabaseRegistryImpl;
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
import java.util.List;

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
 *   <li>{@link DatabaseRegistry} — multi-datasource routing</li>
 *   <li>{@link Pool} — built-in; override via extension module with {@code .primary()}</li>
 *   <li>{@link Dialect} — auto-detected from JDBC URL or overridden via {@link ConfigKeys#DIALECT}</li>
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
        // Declared vocabulary for the unknown-key check.
        binder.contribute(KnownKeys.class).add(KnownKeys.of(ConfigKeys.class, ConfigKeys.PREFIX));
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
            .bind(DatabaseRegistry.class)
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
                return new DatabaseRegistryImpl(named);
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

    private static final SymbolSpec<String> URL =
        SymbolSpec.required(ConfigKeys.URL, String.class, Function.identity());
    private static final SymbolSpec<String> USERNAME =
        SymbolSpec.required(ConfigKeys.USERNAME, String.class, Function.identity());
    private static final SymbolSpec<Integer> POOL_MAX_SIZE =
        SymbolSpec.of(ConfigKeys.POOL_MAX_SIZE, Integer.class,
            PoolConfig.DEFAULT_MAX_SIZE, Integer::parseInt);
    private static final SymbolSpec<Integer> POOL_MIN_IDLE =
        SymbolSpec.of(ConfigKeys.POOL_MIN_IDLE, Integer.class,
            PoolConfig.DEFAULT_MIN_IDLE, Integer::parseInt);
    // Duration keys: no per-key parser — the chain's Coercer resolves them
    // ("2s" syntax, user-registered rules) via one-step resolve(spec).
    private static final SymbolSpec<Duration> POOL_CONNECTION_TIMEOUT =
        SymbolSpec.of(ConfigKeys.POOL_CONNECTION_TIMEOUT, Duration.class,
            PoolConfig.DEFAULT_CONNECTION_TIMEOUT);
    private static final SymbolSpec<Duration> POOL_MAX_LIFETIME =
        SymbolSpec.of(ConfigKeys.POOL_MAX_LIFETIME, Duration.class,
            PoolConfig.DEFAULT_MAX_LIFETIME);
    private static final SymbolSpec<Duration> POOL_MAX_IDLE_TIME =
        SymbolSpec.of(ConfigKeys.POOL_MAX_IDLE_TIME, Duration.class,
            PoolConfig.DEFAULT_MAX_IDLE_TIME);
    private static final SymbolSpec<Duration> POOL_CLEAN_INTERVAL =
        SymbolSpec.of(ConfigKeys.POOL_CLEAN_INTERVAL, Duration.class,
            PoolConfig.DEFAULT_CLEAN_INTERVAL);
    private static final SymbolSpec<Duration> POOL_HEALTH_CHECK_TIMEOUT =
        SymbolSpec.of(ConfigKeys.POOL_HEALTH_CHECK_TIMEOUT, Duration.class,
            PoolConfig.DEFAULT_HEALTH_CHECK_TIMEOUT);
    private static final SymbolSpec<Duration> QUERY_TIMEOUT =
        SymbolSpec.of(ConfigKeys.QUERY_TIMEOUT, Duration.class,
            PoolConfig.DEFAULT_QUERY_TIMEOUT);
    private static final SymbolSpec<Boolean> MIGRATION_ENABLED =
        SymbolSpec.of(ConfigKeys.MIGRATION_ENABLED, Boolean.class, true);
    private static final SymbolSpec<Boolean> SCHEMA_AUTO =
        SymbolSpec.of(ConfigKeys.SCHEMA_AUTO, Boolean.class, true);
    private static final SymbolSpec<List<String>> SCHEMA_GROUPS =
        SymbolSpec.list(ConfigKeys.SCHEMA_GROUPS, List.of());

    private static PoolConfig buildConfig(Container container) {
        SymbolSource s = container.get(SymbolSource.class);
        return new PoolConfig(
            s.resolve(URL),
            s.resolve(USERNAME),
            s.resolve(ConfigKeys.PASSWORD, ""),
            s.resolve(POOL_MAX_SIZE),
            s.resolve(POOL_MIN_IDLE),
            s.resolve(POOL_CONNECTION_TIMEOUT),
            s.resolve(POOL_MAX_LIFETIME),
            s.resolve(POOL_MAX_IDLE_TIME),
            s.resolve(POOL_CLEAN_INTERVAL),
            s.resolve(ConfigKeys.POOL_HEALTH_CHECK_QUERY, null),
            s.resolve(POOL_HEALTH_CHECK_TIMEOUT),
            s.resolve(QUERY_TIMEOUT)
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
        // The lock TTL's unset value is null (the runner applies its own
        // default); the list/coercer-backed read stays a raw resolve so an
        // absent key never parses as Duration zero.
        String lockTtlRaw = s.resolve(ConfigKeys.MIGRATION_LOCK_TTL, "");
        return new MigrationRunner(
            container.get(Database.class),
            MigrationRunner.Options.defaults()
                .withEnabled(s.resolve(MIGRATION_ENABLED))
                .withPath(s.resolve(
                    ConfigKeys.MIGRATION_PATH, MigrationRunner.Options.DEFAULT_PATH))
                .withTable(s.resolve(
                    ConfigKeys.MIGRATION_TABLE, MigrationRunner.Options.DEFAULT_TABLE))
                .withLockTtl(
                    lockTtlRaw.isBlank()
                        ? null // Options restores the default lease
                        : container.get(Coercer.class)
                            .coerce(lockTtlRaw.trim(), Duration.class))
        );
    }

    private static void runSchema(Container container) {
        SymbolSource s = container.get(SymbolSource.class);
        if (!s.resolve(SCHEMA_AUTO)) {
            return;
        }
        var entities = container.extension(SchemaEntity.class).all();
        if (entities.isEmpty()) {
            return;
        }

        Set<String> enabledGroups = Set.copyOf(s.resolve(SCHEMA_GROUPS));

        Database db = container.get(Database.class);
        int total = 0;
        for (SchemaEntity se : entities) {
            if (se.entityTypes().length == 0) continue;

            if (!enabledGroups.isEmpty() && !enabledGroups.contains(se.name())) {
                LOG.debug("Schema group '{}' skipped (not in {})", se.name(), ConfigKeys.SCHEMA_GROUPS);
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
     *
     * <p>An explicit {@code freeway.db.dialect} always wins. Without one, an
     * unrecognized JDBC URL scheme (e.g. {@code jdbc:oracle:...}) makes URL
     * detection throw with guidance (see {@link Dialect#of(String)})
     * instead of silently falling back to PostgreSQL — the container fails
     * fast at startup rather than emitting wrong-dialect SQL at runtime.
     */
    static Dialect resolveDialect(Container container) {
        SymbolSource s = container.get(SymbolSource.class);
        String configured = s.resolve(ConfigKeys.DIALECT, "");
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
        String url = s.resolve(ConfigKeys.URL, "");
        return Dialect.of(url).dialectId();
    }


    /**
     * Configuration keys for the DB module.
     * All keys share the {@code freeway.db} namespace.
     */
    public static final class ConfigKeys {
        private ConfigKeys() {}

        public static final String PREFIX = "freeway.db";

        // ── Connection ────────────────────────────────────────────

        public static final String URL      = "freeway.db.url";
        public static final String USERNAME = "freeway.db.username";
        public static final String PASSWORD = "freeway.db.password";

        // ── Pool ──────────────────────────────────────────────────

        public static final String POOL_MAX_SIZE            = "freeway.db.pool.max-size";
        public static final String POOL_MIN_IDLE            = "freeway.db.pool.min-idle";
        public static final String POOL_CONNECTION_TIMEOUT  = "freeway.db.pool.connection-timeout";
        public static final String POOL_MAX_LIFETIME        = "freeway.db.pool.max-lifetime";
        public static final String POOL_MAX_IDLE_TIME       = "freeway.db.pool.max-idle-time";
        public static final String POOL_CLEAN_INTERVAL      = "freeway.db.pool.clean-interval";
        public static final String POOL_HEALTH_CHECK_QUERY  = "freeway.db.pool.health-check-query";
        public static final String POOL_HEALTH_CHECK_TIMEOUT = "freeway.db.pool.health-check-timeout";
        public static final String QUERY_TIMEOUT            = "freeway.db.query-timeout";

        // ── Migration ─────────────────────────────────────────────

        public static final String MIGRATION_ENABLED = "freeway.db.migration.enabled";
        public static final String MIGRATION_PATH    = "freeway.db.migration.path";
        public static final String MIGRATION_TABLE   = "freeway.db.migration.table";
        /** ISO-8601 duration (e.g. PT1H); empty = runner default (1 hour).
         *  Zero or negative disables stale-lock takeover. */
        public static final String MIGRATION_LOCK_TTL = "freeway.db.migration.lock-ttl";

        // ── Schema ────────────────────────────────────────────────

        public static final String SCHEMA_AUTO   = "freeway.db.schema.auto";
        public static final String SCHEMA_GROUPS = "freeway.db.schema.groups";

        // ── Dialect ───────────────────────────────────────────────

        public static final String DIALECT = "freeway.db.dialect";
    }
}
