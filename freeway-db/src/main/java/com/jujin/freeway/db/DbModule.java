package com.jujin.freeway.db;

import com.jujin.freeway.commons.coercion.CoerceRule;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.db.internal.DatabaseHubImpl;
import com.jujin.freeway.db.internal.DatabaseImpl;
import com.jujin.freeway.db.internal.PoolDefault;
import com.jujin.freeway.db.internal.RowMapperResolver;
import com.jujin.freeway.db.migration.MigrationRunner;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Extension;
import com.jujin.freeway.ioc.Module2;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class DbModule implements Module2{

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

        // database
        binder
            .bind(RowMapperResolver.class)
            .to(container -> buildResolver(container));
        binder
            .bind(Database.class)
            .to(container -> buildDatabase(container));
        binder
            .bind(DatabaseHub.class)
            .to(container -> buildHub(container));
        binder
            .bind(Orm.class)
            .to(Orm.class);
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
        Pool pool = resolvePool(container, poolId, config);
        return new DatabaseImpl(config, resolver, pool);
    }

    private static Pool resolvePool(
        Container container,
        String poolId,
        PoolConfig config
    ) {
        String id = poolId != null && !poolId.isBlank() ? poolId : "builtin";
        try {
            return container.get(Pool.class, id);
        } catch (RuntimeException ex) {
            if (!"builtin".equals(id)) {
                throw new IllegalStateException(
                    "Unable to resolve pool engine '" + id + "'", ex
                );
            }
            return new PoolDefault(config);
        }
    }

    @SuppressWarnings("unchecked")
    private static RowMapperResolver buildResolver(Container container) {
        Map<Class<?>, RowMapper<?>> map = new LinkedHashMap<>();
        try {
            Extension<RowMapping> reg = container.get(
                Extension.class,
                RowMapping.class.getName()
            );
            for (RowMapping entry : reg.all())
                map.put(entry.type(), entry.mapper());
        } catch (IllegalArgumentException ignored) {}
        return new RowMapperResolver(
            container.get(Coercer.class),
            Map.of(),
            map
        );
    }

    @SuppressWarnings("unchecked")
    private static DatabaseHubImpl buildHub(Container container) {
        Map<String, Database> map = new LinkedHashMap<>();
        try {
            Extension<DatabaseNamed> reg = container.get(
                Extension.class,
                DatabaseNamed.class.getName()
            );
            for (DatabaseNamed entry : reg.all())
                map.put(entry.name(), entry.db());
        } catch (IllegalArgumentException ignored) {}
        return new DatabaseHubImpl(map);
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
        try {
            return Boolean.parseBoolean(symbols.resolve(key));
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private static String resolveStr(
        SymbolSource symbols,
        String key,
        String defaultVal
    ) {
        try {
            return symbols.resolve(key);
        } catch (IllegalArgumentException e) {
            return defaultVal;
        }
    }

    private static int parseInt(
        SymbolSource symbols,
        String key,
        int defaultVal
    ) {
        try {
            return Integer.parseInt(symbols.resolve(key));
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private static Duration parseDuration(
        SymbolSource symbols,
        String key,
        Duration defaultVal
    ) {
        try {
            return parseDuration(symbols.resolve(key));
        } catch (Exception e) {
            return defaultVal;
        }
    }

    static Duration parseDuration(String text) {
        if (text == null || text.isBlank()) return null;
        String value = text.trim();
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
}
