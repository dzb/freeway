package com.jujin.freeway.db;

import com.jujin.freeway.commons.scalar.CoercerDefault;
import com.jujin.freeway.db.internal.DatabaseImpl;
import com.jujin.freeway.db.internal.RowMapperResolver;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class DatabaseBuilder {
    private String url;
    private String username;
    private String password = "";
    private int maxSize = DatabaseConfig.DEFAULT_MAX_SIZE;
    private int minIdle = DatabaseConfig.DEFAULT_MIN_IDLE;
    private Duration connectionTimeout = DatabaseConfig.DEFAULT_CONNECTION_TIMEOUT;
    private Duration maxLifetime = DatabaseConfig.DEFAULT_MAX_LIFETIME;
    private Duration maxIdleTime = DatabaseConfig.DEFAULT_MAX_IDLE_TIME;
    private Duration cleanInterval = DatabaseConfig.DEFAULT_CLEAN_INTERVAL;
    private String healthCheckQuery = DatabaseConfig.DEFAULT_HEALTH_CHECK_QUERY;
    private Duration healthCheckTimeout = DatabaseConfig.DEFAULT_HEALTH_CHECK_TIMEOUT;
    private Duration queryTimeout = DatabaseConfig.DEFAULT_QUERY_TIMEOUT;
    private final Map<Class<?>, RowMapper<?>> rowMappers = new LinkedHashMap<>();

    public static DatabaseBuilder from(DatabaseConfig config) {
        return new DatabaseBuilder().copyFrom(Objects.requireNonNull(config, "config"));
    }

    public DatabaseBuilder url(String url) {
        this.url = url;
        return this;
    }

    public DatabaseBuilder username(String username) {
        this.username = username;
        return this;
    }

    public DatabaseBuilder password(String password) {
        this.password = password;
        return this;
    }

    public DatabaseBuilder maxSize(int maxSize) {
        this.maxSize = maxSize;
        return this;
    }

    public DatabaseBuilder minIdle(int minIdle) {
        this.minIdle = minIdle;
        return this;
    }

    public DatabaseBuilder connectionTimeout(Duration timeout) {
        this.connectionTimeout = timeout;
        return this;
    }

    public DatabaseBuilder maxLifetime(Duration lifetime) {
        this.maxLifetime = lifetime;
        return this;
    }

    public DatabaseBuilder maxIdleTime(Duration idleTime) {
        this.maxIdleTime = idleTime;
        return this;
    }

    public DatabaseBuilder cleanInterval(Duration interval) {
        this.cleanInterval = interval;
        return this;
    }

    public DatabaseBuilder healthCheckQuery(String query) {
        this.healthCheckQuery = query;
        return this;
    }

    public DatabaseBuilder healthCheckTimeout(Duration timeout) {
        this.healthCheckTimeout = timeout;
        return this;
    }

    public DatabaseBuilder queryTimeout(Duration timeout) {
        this.queryTimeout = timeout;
        return this;
    }

    public <T> DatabaseBuilder rowMapper(Class<T> type, RowMapper<? extends T> mapper) {
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

    private DatabaseBuilder copyFrom(DatabaseConfig config) {
        this.url = config.url();
        this.username = config.username();
        this.password = config.password();
        this.maxSize = config.maxSize();
        this.minIdle = config.minIdle();
        this.connectionTimeout = config.connectionTimeout();
        this.maxLifetime = config.maxLifetime();
        this.maxIdleTime = config.maxIdleTime();
        this.cleanInterval = config.cleanInterval();
        this.healthCheckQuery = config.healthCheckQuery();
        this.healthCheckTimeout = config.healthCheckTimeout();
        this.queryTimeout = config.queryTimeout();
        return this;
    }

    public Database build() {
        DatabaseConfig config = new DatabaseConfig(
            url,
            username,
            password,
            maxSize,
            minIdle,
            connectionTimeout,
            maxLifetime,
            maxIdleTime,
            cleanInterval,
            healthCheckQuery,
            healthCheckTimeout,
            queryTimeout
        );
        return new DatabaseImpl(
            config,
            new RowMapperResolver(
                new CoercerDefault(),
                Map.copyOf(rowMappers),
                Map.<Class<?>, RowMapper<?>>of()
            )
        );
    }
}
