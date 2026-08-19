package com.jujin.freeway.cloud.config;

import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Runtime dynamic config source — distinct from the immutable startup snapshot
 * ({@code AppConfig}). Supports subscription and reload; a dynamic
 * {@code SymbolProvider} contribution feeds {@code @Value}/{@code @Symbol}.
 *
 * <p>{@code asMap()} is allowed here (unlike {@code SecretStore}, which must
 * never bulk-expose values).
 */
public interface CloudConfig {

    /** Current value for {@code key}, if set. */
    Optional<String> get(String key);

    /** Immutable snapshot of the current values. */
    Map<String, String> asMap();

    /**
     * Subscribes to changes of {@code key}. Implementations may ignore
     * subscriptions (returning {@link ConfigSubscription#NOOP}).
     */
    default ConfigSubscription watch(String key, Consumer<String> listener) {
        return ConfigSubscription.NOOP;
    }

    /** Re-reads the backing source. Called automatically by watch-capable implementations. */
    default void reload() {
    }
}
