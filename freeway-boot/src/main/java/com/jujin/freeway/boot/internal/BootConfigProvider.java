package com.jujin.freeway.boot.internal;

import com.jujin.freeway.ioc.symbol.SymbolProvider;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * One boot config tier as a {@link SymbolProvider}: CLI arguments, mapped
 * environment variables, or the file tier — whatever the layer represents,
 * with its declared {@code SymbolProvider} order ({@code TIER_CLI} /
 * {@code TIER_ENV} / {@code TIER_FILES}).
 *
 * <p>Values come from a {@link Supplier} so the provider re-reads the tier
 * on every lookup: the dynamic file tier returns a fresh snapshot per call
 * (that is how hot reload reaches the symbol chain), static tiers return a
 * constant map.
 *
 * <p>Internal helper of the boot config wiring ({@code AppConfigDefault},
 * {@code AppConfig#symbolProviders()}) — not part of the public API.
 */
public final class BootConfigProvider implements SymbolProvider {

    private final Supplier<Map<String, String>> values;
    private final int order;

    public BootConfigProvider(Supplier<Map<String, String>> values, int order) {
        this.values = Objects.requireNonNull(values, "values");
        this.order = order;
    }

    @Override
    public String lookup(String name) {
        return values.get().get(name);
    }

    @Override
    public int order() {
        return order;
    }
}
