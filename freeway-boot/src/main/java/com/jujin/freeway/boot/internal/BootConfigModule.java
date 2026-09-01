package com.jujin.freeway.boot.internal;

import com.jujin.freeway.boot.AppConfig;
import com.jujin.freeway.boot.AppConfigDynamic;
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
        // One SymbolProvider per tier with a declared order — precedence
        // comes from the tier, never from module install order. Each provider
        // re-reads its layer on every lookup, so the dynamic file tier's hot
        // reload reaches the symbol chain with zero extra machinery.
        for (AppConfig.ConfigLayer layer : config.layers()) {
            binder.contribute(SymbolProvider.class)
                .add(new BootConfigProvider(layer::current, orderOf(layer.name())));
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

    /** Maps a config tier name to its declared symbol order. */
    private static int orderOf(String layerName) {
        return switch (layerName) {
            case AppConfigDynamic.NAME_CLI -> SymbolProvider.TIER_CLI;
            case AppConfigDynamic.NAME_ENV -> SymbolProvider.TIER_ENV;
            case AppConfigDynamic.NAME_FILES -> SymbolProvider.TIER_FILES;
            // Unlayered custom loaders report a single "merged" layer —
            // keep their legacy top-of-cascade precedence.
            default -> SymbolProvider.TIER_CLI;
        };
    }
}
