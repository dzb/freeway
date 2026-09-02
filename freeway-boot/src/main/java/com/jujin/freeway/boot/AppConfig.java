package com.jujin.freeway.boot;

import com.jujin.freeway.ioc.symbol.SymbolProvider;

import java.util.List;
import java.util.Map;

/**
 * The loaded configuration cascade: active profiles, an unmodifiable
 * snapshot, and the lifecycle of its sources.
 *
 * <p><b>This is not a read API.</b> Whatever the source format (properties,
 * JSON, env mapping, CLI), the cascade normalizes everything to
 * {@code key=value} and {@code SymbolSource} is the single entry point for
 * reading values — one precedence chain for {@code @Symbol}/{@code @Value}
 * injection, module sources (secrets), and direct lookups. Typed reading is
 * an explicit post-processing step: declare a {@code ConfigSpec} and parse
 * the resolved value ({@code spec.parse(symbols.resolve(spec.key(), null))}).
 *
 * <p>What this interface owns instead: {@link #profiles()} (boot-level
 * lifecycle metadata the chain cannot know), {@link #asMap()} (the cascade
 * snapshot — the chain cannot enumerate keys, and secret-backed values must
 * never leak into a map; treat the map as the file-tier picture),
 * {@link #symbolProviders()} (how the cascade feeds the chain) and
 * {@link #close()} (stops the hot-reload watcher).
 */
public interface AppConfig {

    /** Returns the active profiles in priority order, as an unmodifiable list. */
    List<String> profiles();

    /**
     * Returns the cascade snapshot (CLI, env and file tiers merged) as an
     * unmodifiable map. Implementations must return a snapshot — mutations to
     * the returned map are not supported and modifying the source after this
     * call must not affect the returned map.
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
