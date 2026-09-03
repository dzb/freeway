package com.jujin.freeway.boot.internal;

import com.jujin.freeway.boot.AppConfig;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;
import com.jujin.freeway.ioc.symbol.SymbolProvider;
import java.util.Objects;

@Marker(Builtin.class)
public final class BootConfigModule implements ModuleEx {
    private final AppConfig config;

    public BootConfigModule(AppConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public void bind(Binder binder) {
        binder.bind(AppConfig.class).to(config);
        binder.bind(HookLifecycle.class).to(container -> new HookLifecycle(container));
        // The config declares its own symbol sources with their orders —
        // precedence comes from the declaration, never from module install
        // order, and a hot-reloading config's sources read live snapshots.
        for (SymbolProvider provider : config.providers()) {
            binder.contribute(SymbolProvider.class).add(provider);
        }
        binder.contribute(RuntimeHook.class)
            .add("freeway.config", new RuntimeHook() {
                @Override
                public void start(Container container) {
                }

                @Override
                public void stop(Container container) {
                    config.close(); // stop the hot-reload watcher, if any
                }
            });
    }
}
