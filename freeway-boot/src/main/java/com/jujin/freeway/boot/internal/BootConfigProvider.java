package com.jujin.freeway.boot.internal;

import com.jujin.freeway.boot.AppConfig;
import com.jujin.freeway.ioc.symbol.SymbolProvider;
import java.util.Objects;

/**
 * Bridges {@link AppConfig} into symbol resolution as the top tier
 * ({@code order()} 0): CLI args, environment and profile/file values outrank
 * every other contributed provider — matching the documented config cascade.
 *
 * <p>Declared as a named class (not a lambda) so the order is explicit:
 * precedence must not depend on which module happens to be installed first.
 */
final class BootConfigProvider implements SymbolProvider {

    private final AppConfig config;

    BootConfigProvider(AppConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public String lookup(String name) {
        return config.get(name);
    }

    @Override
    public int order() {
        return 0;
    }
}
