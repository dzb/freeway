package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.config.CloudConfig;
import com.jujin.freeway.ioc.symbol.SymbolProvider;

import java.util.Objects;

/**
 * Dynamic {@link SymbolProvider} backed by {@link CloudConfig}: makes the
 * latest config value visible to {@code @Value}/{@code @Symbol} resolution.
 *
 * <p>Registered via {@code contribute(SymbolProvider.class).add(Class)} — the
 * ioc on-demand facade defers creation to first lookup, and the config file
 * path never flows back through symbol resolution (see CloudConfigModule), so
 * there is no provider recursion.
 */
public final class CloudConfigSymbolProvider implements SymbolProvider {

    private final CloudConfig config;

    public CloudConfigSymbolProvider(CloudConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public String lookup(String name) {
        return config.get(name).orElse(null);
    }

    /** Consulted after the secret provider — config yields to secrets. */
    @Override
    public int order() {
        return 20;
    }
}
