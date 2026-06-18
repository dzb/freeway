package com.jujin.freeway.db;

import com.jujin.freeway.commons.coercion.CoerceRule;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.db.internal.DatabaseHubImpl;
import com.jujin.freeway.db.internal.DatabaseImpl;
import com.jujin.freeway.db.internal.PoolDefault;
import com.jujin.freeway.db.internal.RowMapperResolver;
import com.jujin.freeway.db.migration.MigrationRunner;
import com.jujin.freeway.db.schema.Dialect;
import com.jujin.freeway.db.schema.PostgresDialect;
import com.jujin.freeway.db.schema.Schema;
import com.jujin.freeway.db.schema.SchemaEntity;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Module2;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import java.time.Duration;
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
        SymbolSource symbols = container.get(SymbolSource.class);
        String url = Objects.requireNonNull(
            symbols.resolve("freeway.db.url"),
            "freeway.db.url is required"
        );
        String user = Objects.requireNonNull(
            symbols.resolve("freeway.db.username"),
            "freeway.db.username is required"
        );
        return new PoolConfig(
            url,
            user,
            resolveStr(symbols, "freeway.db.password", ""),
            parseInt(
                symbols,
                "freeway.db.pool.max-size",
                PoolConfig.DEFAULT_MAX_SIZE
            ),
            parseInt(
                symbols,
                "freeway.db.pool.min-idle",
                PoolConfig.DEFAULT_MIN_IDLE
            ),
            parseDuration(
                symbols,
                "freeway.db.pool.connection-timeout",
                PoolConfig.DEFAULT_CONNECTION_TIMEOUT
            ),
            parseDuration(
                symbols,
                "freeway.db.pool.max-lifetime",
                PoolConfig.DEFAULT_MAX_LIFETIME
            ),
            parseDuration(
                symbols,
                "freeway.db.pool.max-idle-time",
                PoolConfig.DEFAULT_MAX_IDLE_TIME
            ),
            parseDuration(
                symbols,
                "freeway.db.pool.clean-interval",
                PoolConfig.DEFAULT_CLEAN_INTERVAL
            ),
            resolveStr(symbols, "freeway.db.pool.health-check-query", null),
            parseDuration(
                symbols,
                "freeway.db.pool.health-check-timeout",
                PoolConfig.DEFAULT_HEALTH_CHECK_TIMEOUT
            ),
            parseDuration(
                symbols,
                "freeway.db.query-timeout",
                PoolConfig.DEFAULT_QUERY_TIMEOUT
            )
        );
    }

    private static Database buildDatabase(Container container) {
        PoolConfig config = container.get(PoolConfig.class);
        RowMapperResolver resolver = container.get(RowMapperResolver.class);
        SymbolSource symbols = container.get(SymbolSource.class);
        String poolId = resolveStr(symbols, "freeway.db.pool", "builtin");
        Pool pool = resolvePool(container, poolId);
        return new DatabaseImpl(config, resolver, pool);
    }

    private static Pool resolvePool(
        Container container,
        String poolId
    ) {
        String id = poolId != null && !poolId.isBlank() ? poolId : "builtin";
        try {
            return container.get(Pool.class, id);
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                "Unable to resolve pool engine '" + id + "'",
                ex
            );
        }
    }

    private static MigrationRunner buildMigrationRunner(Container container) {
        SymbolSource symbols = container.get(SymbolSource.class);
        return new MigrationRunner(
            container.get(Database.class),
            parseBool(symbols, "freeway.db.migration.enabled", true),
            resolveStr(symbols, "freeway.db.migration.path", "db/migration/"),
            resolveStr(symbols, "freeway.db.migration.table", "_migrations")
        );
    }

    private static boolean parseBool(
        SymbolSource symbols,
        String key,
        boolean defaultVal
    ) {
        String text = resolveStr(symbols, key, null);
        if (text == null) {
            return defaultVal;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(key + " must not be blank");
        }
        if (
            "true".equalsIgnoreCase(value) ||
            "yes".equalsIgnoreCase(value) ||
            "on".equalsIgnoreCase(value) ||
            "1".equals(value)
        ) {
            return true;
        }
        if (
            "false".equalsIgnoreCase(value) ||
            "no".equalsIgnoreCase(value) ||
            "off".equalsIgnoreCase(value) ||
            "0".equals(value)
        ) {
            return false;
        }
        throw new IllegalArgumentException("Invalid boolean value for " + key + ": " + text);
    }

    private static String resolveStr(
        SymbolSource symbols,
        String key,
        String defaultVal
    ) {
        try {
            return symbols.resolve(key);
        } catch (IllegalArgumentException e) {
            if (isUnknownSymbol(e, key)) {
                return defaultVal;
            }
            throw e;
        }
    }

    private static int parseInt(
        SymbolSource symbols,
        String key,
        int defaultVal
    ) {
        String text = resolveStr(symbols, key, null);
        if (text == null) {
            return defaultVal;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(key + " must not be blank");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Invalid integer value for " + key + ": " + text,
                e
            );
        }
    }

    private static Duration parseDuration(
        SymbolSource symbols,
        String key,
        Duration defaultVal
    ) {
        String text = resolveStr(symbols, key, null);
        if (text == null) {
            return defaultVal;
        }
        return parseDuration(text);
    }

    static Duration parseDuration(String text) {
        if (text == null) {
            return null;
        }
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

    private static boolean isUnknownSymbol(IllegalArgumentException e, String key) {
        String message = e.getMessage();
        return message != null && message.equals("Unknown symbol: " + key);
    }

    private static void runSchema(Container container) {
        SymbolSource symbols = container.get(SymbolSource.class);
        boolean auto = parseBool(symbols, "freeway.db.schema.auto", true);
        if (!auto) {
            return;
        }
        var entities = container.extension(SchemaEntity.class).all();
        if (entities.isEmpty()) {
            return;
        }

        // Optional group filter — only run named groups
        Set<String> enabledGroups = parseGroupFilter(
            resolveStr(symbols, "freeway.db.schema.groups", "")
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
        SymbolSource symbols = container.get(SymbolSource.class);
        String dialectId = resolveStr(symbols, "freeway.db.dialect", "");
        if (dialectId.isBlank()) {
            dialectId = detectDialect(symbols);
        }
        if (!dialectId.isBlank()) {
            try {
                return container.get(Dialect.class, dialectId);
            } catch (RuntimeException ex) {
                throw new IllegalStateException(
                    "Unable to resolve dialect '" + dialectId + "'",
                    ex
                );
            }
        }
        return container.get(Dialect.class);
    }

    static String detectDialect(SymbolSource symbols) {
        String url = resolveStr(symbols, "freeway.db.url", "");
        if (url.contains(":postgresql:")) return "postgresql";
        if (url.contains(":mysql:") || url.contains(":mariadb:")) return "mysql";
        if (url.contains(":h2:")) return "h2";
        if (url.contains(":sqlite:")) return "sqlite";
        return "";
    }

    private static Set<String> parseGroupFilter(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> set = new java.util.LinkedHashSet<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                set.add(trimmed);
            }
        }
        return Set.copyOf(set);
    }
}
