package com.jujin.freeway2.db;

import com.jujin.freeway2.ioc.annotation.Value;
import java.time.Duration;
import java.util.Objects;

public record DatabaseConfig(
    String url,
    String username,
    String password,
    int maxSize,
    int minIdle,
    Duration connectionTimeout,
    Duration maxLifetime,
    Duration maxIdleTime,
    Duration cleanInterval,
    String healthCheckQuery,
    Duration healthCheckTimeout,
    Duration queryTimeout
) {
    public static final String PREFIX = "freeway.db";

    public static final int DEFAULT_MAX_SIZE = 10;
    public static final int DEFAULT_MIN_IDLE = 2;
    public static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_MAX_LIFETIME = Duration.ofMinutes(30);
    public static final Duration DEFAULT_MAX_IDLE_TIME = Duration.ofMinutes(10);
    public static final Duration DEFAULT_CLEAN_INTERVAL = Duration.ofMinutes(2);
    public static final String DEFAULT_HEALTH_CHECK_QUERY = null;
    public static final Duration DEFAULT_HEALTH_CHECK_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_QUERY_TIMEOUT = Duration.ofSeconds(15);

    public DatabaseConfig(
        @Value("${" + PREFIX + ".url}") String url,
        @Value("${" + PREFIX + ".username}") String username,
        @Value("${" + PREFIX + ".password:}") String password,
        @Value("${" + PREFIX + ".pool.max-size:10}") int maxSize,
        @Value("${" + PREFIX + ".pool.min-idle:2}") int minIdle,
        @Value("${" + PREFIX + ".pool.connection-timeout:10s}") Duration connectionTimeout,
        @Value("${" + PREFIX + ".pool.max-lifetime:30m}") Duration maxLifetime,
        @Value("${" + PREFIX + ".pool.max-idle-time:10m}") Duration maxIdleTime,
        @Value("${" + PREFIX + ".pool.clean-interval:2m}") Duration cleanInterval,
        @Value("${" + PREFIX + ".pool.health-check-query:}") String healthCheckQuery,
        @Value("${" + PREFIX + ".pool.health-check-timeout:5s}") Duration healthCheckTimeout,
        @Value("${" + PREFIX + ".query-timeout:15s}") Duration queryTimeout
    ) {
        this.url = requireText(url, "url");
        this.username = requireText(username, "username");
        this.password = password == null ? "" : password;
        this.maxSize = requirePositive(maxSize, "maxSize");
        this.minIdle = requireRange(minIdle, 0, this.maxSize, "minIdle");
        this.connectionTimeout = requireDuration(connectionTimeout, "connectionTimeout");
        this.maxLifetime = requireDuration(maxLifetime, "maxLifetime");
        this.maxIdleTime = requireDuration(maxIdleTime, "maxIdleTime");
        this.cleanInterval = requireDuration(cleanInterval, "cleanInterval");
        this.healthCheckQuery = healthCheckQuery == null || healthCheckQuery.isBlank()
            ? null
            : healthCheckQuery.trim();
        this.healthCheckTimeout = requireDuration(healthCheckTimeout, "healthCheckTimeout");
        this.queryTimeout = requireDuration(queryTimeout, "queryTimeout");
    }

    public static DatabaseConfig defaults(String url, String username, String password) {
        return new DatabaseConfig(
            url,
            username,
            password,
            DEFAULT_MAX_SIZE,
            DEFAULT_MIN_IDLE,
            DEFAULT_CONNECTION_TIMEOUT,
            DEFAULT_MAX_LIFETIME,
            DEFAULT_MAX_IDLE_TIME,
            DEFAULT_CLEAN_INTERVAL,
            DEFAULT_HEALTH_CHECK_QUERY,
            DEFAULT_HEALTH_CHECK_TIMEOUT,
            DEFAULT_QUERY_TIMEOUT
        );
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return trimmed;
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static int requireRange(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                name + " must be between " + min + " and " + max
            );
        }
        return value;
    }

    private static Duration requireDuration(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
