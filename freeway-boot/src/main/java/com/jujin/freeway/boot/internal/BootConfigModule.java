package com.jujin.freeway.boot.internal;

import com.jujin.freeway.boot.AppConfig;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Module;
import com.jujin.freeway.ioc.SymbolProviders;
import java.util.Objects;

public final class BootConfigModule implements Module {
    private final AppConfig config;

    public BootConfigModule(AppConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public void bind(Binder binder) {
        binder.bind(AppConfig.class).to(config);
        binder.bind(HookLifecycle.class).to(HookLifecycle.class);
        binder.contribute(SymbolProviders.class).add(config::get);
    }
}
