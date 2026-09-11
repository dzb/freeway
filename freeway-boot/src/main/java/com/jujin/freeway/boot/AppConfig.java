package com.jujin.freeway.boot;

import com.jujin.freeway.ioc.symbol.SymbolProvider;

import java.util.List;

/**
 * The loaded configuration cascade: active profiles, the symbol sources it
 * contributes to the container, and the lifecycle of those sources.
 *
 * <p><b>This is not a read API.</b> Whatever the source format (properties,
 * JSON, env mapping, CLI), the cascade normalizes everything to
 * {@code key=value} and {@link com.jujin.freeway.ioc.symbol.SymbolSource} is
 * the single entry point for reading values — one precedence chain for
 * {@code @Symbol}/{@code @Value} injection, module sources (secrets), and
 * direct lookups. Typed reading is an explicit post-processing step: declare
 * a {@code SymbolSpec} and parse the resolved value
 * ({@code symbols.resolve(spec)}).
 *
 * <p>What this interface owns instead: {@link #profiles()} (boot-level
 * lifecycle metadata the chain cannot know), {@link #providers()} (how the
 * cascade feeds the chain) and {@link #close()} (stops the hot-reload watcher,
 * if any). Note what is deliberately absent: a map of the resolved values. The
 * chain does not expose one, and a second, map-shaped view of the same cascade
 * would be free to disagree with it — or to leak environment/CLI-injected
 * secrets into a public snapshot.
 *
 * <p>Construction: the framework's cascade loader produces the framework's
 * implementation; for a custom source, call
 * {@link com.jujin.freeway.boot.internal.AppConfigDefault#of(java.util.Map, java.util.List)}
 * or implement this interface and hand it to
 * {@code AppBuilder.config(config)}.
 */
public interface AppConfig extends AutoCloseable {

    /** Returns the active profiles in priority order, as an unmodifiable list. */
    List<String> profiles();

    /**
     * The symbol sources this config contributes to the container, with
     * declared {@link SymbolProvider#order() orders}. This is the config's
     * whole content contract — the chain, not the config, decides precedence,
     * so a source that must be consulted early cannot be silently outranked by
     * module install order.
     *
     * <p>An undifferentiated config behaves like the framework's file tier in
     * one line — {@code List.of(SymbolProvider.of(values::get, TIER_FILES))} —
     * which places it below env/CLI; a module source (e.g. the cloud secret
     * store) slots in between tiers by declaring its own order. The
     * framework's own config ({@code AppConfigDefault}) contributes one source
     * per tier (cli → env → files → preset).
     */
    List<SymbolProvider> providers();

    /**
     * Releases resources held by this config (e.g. a hot-reload watcher).
     * Static configurations hold nothing and keep the default no-op.
     */
    @Override
    default void close() {
    }
}
