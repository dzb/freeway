package com.jujin.freeway.db;

import java.time.Duration;

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
    Duration healthCheckTimeout
) {
    public static PoolConfig from(DatabaseConfig config) {
        return new PoolConfig(
            config.url(),
            config.username(),
            config.password(),
            config.maxSize(),
            config.minIdle(),
            config.connectionTimeout(),
            config.maxLifetime(),
            config.maxIdleTime(),
            config.cleanInterval(),
            config.healthCheckQuery(),
            config.healthCheckTimeout()
        );
    }
}
