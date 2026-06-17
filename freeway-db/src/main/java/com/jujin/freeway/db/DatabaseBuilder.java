package com.jujin.freeway.db;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.db.internal.DatabaseImpl;
import com.jujin.freeway.db.internal.RowMapperResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class DatabaseBuilder {

    private PoolConfig config;
    private Coercer coercer;
    private Pool pool;
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

    public Database build() {
        if (config == null) {
            throw new IllegalStateException("config is required");
        }
        Coercer effective = coercer;
        if (effective == null) {
            CoercerDefault cd = new CoercerDefault();
            for (var rule : Coercions.jdbcDefaults()) {
                cd.register(rule);
            }
            effective = cd;
        }
        return new DatabaseImpl(
            config,
            new RowMapperResolver(
                effective,
                Map.copyOf(rowMappers),
                Map.<Class<?>, RowMapper<?>>of()
            ),
            pool
        );
    }
}
