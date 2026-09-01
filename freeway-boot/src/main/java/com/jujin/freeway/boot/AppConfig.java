package com.jujin.freeway.boot;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.config.ConfigSpec;
import com.jujin.freeway.ioc.symbol.SymbolProvider;

import java.util.List;
import java.util.Map;

/**
 * Application configuration facade: flat key-value lookups, active profiles,
 * and an unmodifiable snapshot.
 *
 * <p><b>Resolution semantics.</b> {@code get}/{@code asMap} cover the config
 * itself (the framework's CLI/env/file tiers). {@code @Value}/{@code @Symbol}
 * resolution additionally consults module-contributed sources (e.g. the cloud
 * secret store) through the {@link SymbolProvider} chain — when a key exists
 * in both, the symbol chain is authoritative. Application code that wants the
 * full precedence should resolve symbols, not {@code AppConfig.get}.
 */
public interface AppConfig {
    /** Returns the configured value for {@code key}, or {@code null} if absent. */
    String get(String key);

    /**
     * Returns the typed value for {@code key}: parsed from the raw string
     * with the key's parser, or the key's default when absent/blank. Errors
     * (missing required key, malformed value) are reported by the key itself
     * with the key name in the message.
     *
     * <p>Specs created without a per-key parser ({@code ConfigSpec.of(key,
     * type, default)}) are resolved with the default {@link Coercer} — the
     * container Coercer's built-in conversions apply; user-registered
     * {@code CoerceRule}s require the container coercer via
     * {@code parse(raw, Coercer)}.</p>
     */
    default <T> T get(ConfigSpec<T> key) {
        if (key.parser() != null) {
            return key.parse(get(key.key()));
        }
        return key.parse(get(key.key()), DefaultCoercer.instance());
    }

    /**
     * Shared coercer for parser-less specs — {@link CoercerDefault} is
     * immutable after construction and thread-safe, so one instance serves
     * every lookup. Held in a nested class because interface fields cannot
     * be private.
     */
    final class DefaultCoercer {

        private static final Coercer INSTANCE = new CoercerDefault();

        private DefaultCoercer() {}

        static Coercer instance() {
            return INSTANCE;
        }
    }

    /** Returns the active profiles in priority order, as an unmodifiable list. */
    List<String> profiles();

    /**
     * Returns the full configuration as an unmodifiable map.
     * Implementations must return a snapshot — mutations to the returned map
     * are not supported and modifying the source after this call must not
     * affect the returned map.
     */
    Map<String, String> asMap();

    /**
     * The symbol sources this config contributes to the container, with
     * declared {@code SymbolProvider} orders. The default reports the merged
     * view as a single source on the file tier ({@code TIER_FILES}) — the
     * behavior third-party {@link ConfigLoader} implementations get for
     * free: an undifferentiated config behaves like the framework's file
     * tier and loses to env/CLI and module sources above it. The framework's
     * own config ({@code AppConfigDefault}) contributes one source per tier
     * (cli → env → files), which is what lets module sources (e.g. the
     * cloud secret store) slot in between tiers by declaring their own order.
     */
    default List<SymbolProvider> symbolProviders() {
        SymbolProvider merged = new SymbolProvider() {
            @Override
            public String lookup(String name) {
                return asMap().get(name);
            }

            @Override
            public int order() {
                return SymbolProvider.TIER_FILES;
            }
        };
        return List.of(merged);
    }

    /**
     * Releases resources held by this config (e.g. a hot-reload watcher).
     * Static configurations hold nothing and keep the default no-op.
     */
    default void close() {
    }
}
