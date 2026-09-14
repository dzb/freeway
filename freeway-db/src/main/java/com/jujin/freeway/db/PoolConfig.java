package com.jujin.freeway.db;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for a JDBC connection pool.
 *
 * <p>{@link #defaults(String, String, String)} states every default; the
 * per-field withers change one knob each, so a call site never has to count
 * twelve positional arguments (and never has to wonder whether the fourth
 * {@link Duration} was the lifetime or the idle time):
 * <pre>{@code
 * var config = PoolConfig.defaults(url, user, pass)
 *     .withMaxSize(20)
 *     .withMinIdle(5)
 *     .withMaxLifetime(Duration.ofMinutes(30));
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
        // queryTimeout is the only duration where 0 is meaningful — it maps to
        // JDBC setQueryTimeout(0), i.e. no statement timeout.
        if (queryTimeout == null || queryTimeout.isNegative()) {
            throw new IllegalArgumentException("queryTimeout must not be negative");
        }

        if (healthCheckQuery != null && healthCheckQuery.isBlank()) healthCheckQuery = null;
    }

    public static PoolConfig defaults(String url, String username, String password) {
        return new PoolConfig(
            url, username, password,
            DEFAULT_MAX_SIZE, DEFAULT_MIN_IDLE, DEFAULT_CONNECTION_TIMEOUT,
            DEFAULT_MAX_LIFETIME, DEFAULT_MAX_IDLE_TIME, DEFAULT_CLEAN_INTERVAL,
            null, // no health-check query: JDBC isValid decides
            DEFAULT_HEALTH_CHECK_TIMEOUT,
            DEFAULT_QUERY_TIMEOUT
        );
    }

    /** Same configuration with {@link #url} replaced. */
    public PoolConfig withUrl(String url) {
        return new PoolConfig(url, username, password, maxSize, minIdle, connectionTimeout, maxLifetime, maxIdleTime, cleanInterval, healthCheckQuery, healthCheckTimeout, queryTimeout);
    }

    /** Same configuration with {@link #username} replaced. */
    public PoolConfig withUsername(String username) {
        return new PoolConfig(url, username, password, maxSize, minIdle, connectionTimeout, maxLifetime, maxIdleTime, cleanInterval, healthCheckQuery, healthCheckTimeout, queryTimeout);
    }

    /** Same configuration with {@link #password} replaced. */
    public PoolConfig withPassword(String password) {
        return new PoolConfig(url, username, password, maxSize, minIdle, connectionTimeout, maxLifetime, maxIdleTime, cleanInterval, healthCheckQuery, healthCheckTimeout, queryTimeout);
    }

    /** Same configuration with {@link #maxSize} replaced. */
    public PoolConfig withMaxSize(int maxSize) {
        return new PoolConfig(url, username, password, maxSize, minIdle, connectionTimeout, maxLifetime, maxIdleTime, cleanInterval, healthCheckQuery, healthCheckTimeout, queryTimeout);
    }

    /** Same configuration with {@link #minIdle} replaced. */
    public PoolConfig withMinIdle(int minIdle) {
        return new PoolConfig(url, username, password, maxSize, minIdle, connectionTimeout, maxLifetime, maxIdleTime, cleanInterval, healthCheckQuery, healthCheckTimeout, queryTimeout);
    }

    /** Same configuration with {@link #connectionTimeout} replaced. */
    public PoolConfig withConnectionTimeout(Duration connectionTimeout) {
        return new PoolConfig(url, username, password, maxSize, minIdle, connectionTimeout, maxLifetime, maxIdleTime, cleanInterval, healthCheckQuery, healthCheckTimeout, queryTimeout);
    }

    /** Same configuration with {@link #maxLifetime} replaced. */
    public PoolConfig withMaxLifetime(Duration maxLifetime) {
        return new PoolConfig(url, username, password, maxSize, minIdle, connectionTimeout, maxLifetime, maxIdleTime, cleanInterval, healthCheckQuery, healthCheckTimeout, queryTimeout);
    }

    /** Same configuration with {@link #maxIdleTime} replaced. */
    public PoolConfig withMaxIdleTime(Duration maxIdleTime) {
        return new PoolConfig(url, username, password, maxSize, minIdle, connectionTimeout, maxLifetime, maxIdleTime, cleanInterval, healthCheckQuery, healthCheckTimeout, queryTimeout);
    }

    /** Same configuration with {@link #cleanInterval} replaced. */
    public PoolConfig withCleanInterval(Duration cleanInterval) {
        return new PoolConfig(url, username, password, maxSize, minIdle, connectionTimeout, maxLifetime, maxIdleTime, cleanInterval, healthCheckQuery, healthCheckTimeout, queryTimeout);
    }

    /** Same configuration with {@link #healthCheckQuery} replaced. */
    public PoolConfig withHealthCheckQuery(String healthCheckQuery) {
        return new PoolConfig(url, username, password, maxSize, minIdle, connectionTimeout, maxLifetime, maxIdleTime, cleanInterval, healthCheckQuery, healthCheckTimeout, queryTimeout);
    }

    /** Same configuration with {@link #healthCheckTimeout} replaced. */
    public PoolConfig withHealthCheckTimeout(Duration healthCheckTimeout) {
        return new PoolConfig(url, username, password, maxSize, minIdle, connectionTimeout, maxLifetime, maxIdleTime, cleanInterval, healthCheckQuery, healthCheckTimeout, queryTimeout);
    }

    /** Same configuration with {@link #queryTimeout} replaced. */
    public PoolConfig withQueryTimeout(Duration queryTimeout) {
        return new PoolConfig(url, username, password, maxSize, minIdle, connectionTimeout, maxLifetime, maxIdleTime, cleanInterval, healthCheckQuery, healthCheckTimeout, queryTimeout);
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
