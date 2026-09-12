package com.jujin.freeway.boot.internal;

import com.jujin.freeway.boot.AppConfig;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;
import com.jujin.freeway.ioc.symbol.SymbolProvider;
import java.util.Objects;

/**
 * Wires the loaded {@link AppConfig} into the container: the config itself plus
 * the symbol sources it declares. That is the whole job — the runtime's other
 * collaborators belong to the runtime, not to the container's service set.
 */
@Marker(Builtin.class)
public final class BootModule implements ModuleEx {
    private final AppConfig config;

    public BootModule(AppConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public void bind(Binder binder) {
        binder.bind(AppConfig.class).to(container -> config);
        // The config declares its own symbol sources with their orders —
        // precedence comes from the declaration, never from module install
        // order, and a hot-reloading config's sources read live snapshots.
        for (SymbolProvider provider : config.providers()) {
            binder.contribute(SymbolProvider.class).add(provider);
        }
    }
}
