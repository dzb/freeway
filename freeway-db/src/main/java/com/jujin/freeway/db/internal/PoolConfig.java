package com.jujin.freeway.db.internal;

import java.time.Duration;
import java.util.Objects;

record PoolConfig(
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
    PoolConfig {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(connectionTimeout, "connectionTimeout");
        Objects.requireNonNull(maxLifetime, "maxLifetime");
        Objects.requireNonNull(maxIdleTime, "maxIdleTime");
        Objects.requireNonNull(cleanInterval, "cleanInterval");
        Objects.requireNonNull(healthCheckTimeout, "healthCheckTimeout");
    }

    static PoolConfig from(com.jujin.freeway.db.DatabaseConfig config) {
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
