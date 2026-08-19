package com.jujin.freeway.cloud.secret;

import com.jujin.freeway.ioc.symbol.SymbolProvider;

import java.util.Objects;

/**
 * Dynamic {@link SymbolProvider} backed by {@link SecretStore}: makes secrets
 * resolvable via {@code @Symbol("db.password")} (and any symbol lookup), with
 * secret-source priority over the config provider (registered before it).
 */
public final class SecretSymbolSource implements SymbolProvider {

    private final SecretStore store;

    public SecretSymbolSource(SecretStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public String lookup(String name) {
        return store.get(name).orElse(null);
    }
}
