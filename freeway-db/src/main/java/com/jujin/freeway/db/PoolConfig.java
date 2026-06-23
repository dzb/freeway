package com.jujin.freeway.db;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for a JDBC connection pool.
 *
 * <p>Create with custom values or use the {@link #defaults(String, String, String)} shortcut:
 * <pre>{@code
 * var config = new PoolConfig(url, user, pass, 20, 5,
 *     Duration.ofSeconds(30), Duration.ofMinutes(30), Duration.ofMinutes(10),
 *     Duration.ofMinutes(2), null, Duration.ofSeconds(5), Duration.ofSeconds(15));
 *
 * // or with defaults:
 * var config = PoolConfig.defaults(url, user, pass);
 * }</pre>
 *
 * @param url                JDBC connection URL
 * @param username           database username
 * @param password           database password (may be empty)
 * @param maxSize            maximum number of connections in the pool
 * @param minIdle            minimum number of idle connections to maintain
 * @param connectionTimeout  maximum time to wait for a connection
 * @param maxLifetime        maximum lifetime of a connection in the pool
 * @param maxIdleTime        maximum time a connection may remain idle
 * @param cleanInterval      interval between idle-eviction cycles
 * @param healthCheckQuery   optional query for connection health checks (null = use JDBC isValid)
 * @param healthCheckTimeout timeout for the health check query
 * @param queryTimeout       default timeout for all queries from this pool
 */
public record PoolConfig(
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
    public static final int DEFAULT_MAX_SIZE = 10;
    public static final int DEFAULT_MIN_IDLE = 2;
    public static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_MAX_LIFETIME = Duration.ofMinutes(30);
    public static final Duration DEFAULT_MAX_IDLE_TIME = Duration.ofMinutes(10);
    public static final Duration DEFAULT_CLEAN_INTERVAL = Duration.ofMinutes(2);
    public static final String DEFAULT_HEALTH_CHECK_QUERY = null;
    public static final Duration DEFAULT_HEALTH_CHECK_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_QUERY_TIMEOUT = Duration.ofSeconds(15);

    public PoolConfig {
        url = requireNonBlank(url, "url");
        username = requireNonBlank(username, "username");
        if (password == null) password = "";

        if (maxSize <= 0) throw new IllegalArgumentException("maxSize must be positive");
        if (minIdle < 0 || minIdle > maxSize)
            throw new IllegalArgumentException("minIdle must be between 0 and " + maxSize);

        requireDuration(connectionTimeout, "connectionTimeout");
        requireDuration(maxLifetime, "maxLifetime");
        requireDuration(maxIdleTime, "maxIdleTime");
        requireDuration(cleanInterval, "cleanInterval");
        requireDuration(healthCheckTimeout, "healthCheckTimeout");
        requireDuration(queryTimeout, "queryTimeout");

        if (healthCheckQuery != null && healthCheckQuery.isBlank()) healthCheckQuery = null;
    }

    public static PoolConfig defaults(String url, String username, String password) {
        return new PoolConfig(
            url, username, password,
            DEFAULT_MAX_SIZE, DEFAULT_MIN_IDLE, DEFAULT_CONNECTION_TIMEOUT,
            DEFAULT_MAX_LIFETIME, DEFAULT_MAX_IDLE_TIME, DEFAULT_CLEAN_INTERVAL,
            DEFAULT_HEALTH_CHECK_QUERY, DEFAULT_HEALTH_CHECK_TIMEOUT,
            DEFAULT_QUERY_TIMEOUT
        );
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return trimmed;
    }

    private static void requireDuration(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative())
            throw new IllegalArgumentException(name + " must be positive");
    }
}
