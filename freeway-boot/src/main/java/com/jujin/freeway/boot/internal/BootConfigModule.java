package com.jujin.freeway.boot.internal;

import com.jujin.freeway.boot.AppConfig;
import com.jujin.freeway.boot.internal.ConfigLoaderDefault.BootConfigLayers;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
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
        // comes from the tier, never from module install order. Cloud tiers
        // (secret 10, dynamic config 20) interleave between env and files.
        for (AppConfig.ConfigLayer layer : config.layers()) {
            binder.contribute(SymbolProvider.class)
                .add(new BootConfigProvider(layer.values(), orderOf(layer.name())));
        }
    }

    /** Maps a config tier name to its declared symbol order. */
    private static int orderOf(String layerName) {
        return switch (layerName) {
            case BootConfigLayers.NAME_CLI -> SymbolProvider.TIER_CLI;
            case BootConfigLayers.NAME_ENV -> SymbolProvider.TIER_ENV;
            case BootConfigLayers.NAME_FILES -> SymbolProvider.TIER_FILES;
            // Unlayered custom loaders report a single "merged" layer —
            // keep their legacy top-of-cascade precedence.
            default -> SymbolProvider.TIER_CLI;
        };
    }
}
