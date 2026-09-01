package com.jujin.freeway.boot.internal;

import com.jujin.freeway.ioc.symbol.SymbolProvider;
import java.util.Map;
import java.util.Objects;

/**
 * One boot config tier as a {@link SymbolProvider}: CLI arguments, mapped
 * environment variables, or local files — whichever tier the layer represents,
 * with its declared {@code SymbolProvider} order ({@code TIER_CLI} /
 * {@code TIER_ENV} / {@code TIER_FILES}).
 *
 * <p>The tiers are contributed separately so precedence is explicit and
 * independent of module install order — and so cloud tiers (secret, dynamic
 * config) can interleave by declared order: CLI → env → secret → dynamic
 * config → local files.
 */
final class BootConfigProvider implements SymbolProvider {

    private final Map<String, String> values;
    private final int order;

    BootConfigProvider(Map<String, String> values, int order) {
        this.values = Map.copyOf(Objects.requireNonNull(values, "values"));
        this.order = order;
    }

    @Override
    public String lookup(String name) {
        return values.get(name);
    }

    @Override
    public int order() {
        return order;
    }
}
