package com.jujin.freeway.cloud.config;

/**
 * Subscription handle returned by {@link CloudConfig#watch}; closing it stops
 * delivery of further change notifications.
 */
@FunctionalInterface
public interface ConfigSubscription extends AutoCloseable {

    ConfigSubscription NOOP = () -> {
    };

    @Override
    void close();
}
