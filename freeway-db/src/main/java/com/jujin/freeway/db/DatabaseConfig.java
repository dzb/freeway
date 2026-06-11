package com.jujin.freeway.db;

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

    public DatabaseConfig {
        requireText(url, "url");
        requireText(username, "username");
        if (password == null) password = "";
        requirePositive(maxSize, "maxSize");
        requireRange(minIdle, 0, maxSize, "minIdle");
        requireDuration(connectionTimeout, "connectionTimeout");
        requireDuration(maxLifetime, "maxLifetime");
        requireDuration(maxIdleTime, "maxIdleTime");
        requireDuration(cleanInterval, "cleanInterval");
        if (healthCheckQuery != null && healthCheckQuery.isBlank()) healthCheckQuery = null;
        requireDuration(healthCheckTimeout, "healthCheckTimeout");
        requireDuration(queryTimeout, "queryTimeout");
    }

    public static DatabaseConfig defaults(String url, String username, String password) {
        return new DatabaseConfig(
            url, username, password,
            DEFAULT_MAX_SIZE, DEFAULT_MIN_IDLE, DEFAULT_CONNECTION_TIMEOUT,
            DEFAULT_MAX_LIFETIME, DEFAULT_MAX_IDLE_TIME, DEFAULT_CLEAN_INTERVAL,
            DEFAULT_HEALTH_CHECK_QUERY, DEFAULT_HEALTH_CHECK_TIMEOUT, DEFAULT_QUERY_TIMEOUT
        );
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return trimmed;
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static int requireRange(int value, int min, int max, String name) {
        if (value < min || value > max)
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        return value;
    }

    private static Duration requireDuration(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative())
            throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
