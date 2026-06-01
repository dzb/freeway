package com.jujin.freeway.boot;

import com.jujin.freeway.ioc.Module;

public final class Launcher {
    private Launcher() {
    }

    public static AppRuntime run(Class<? extends Module> primaryModuleType, String... args) {
        return AppBootstrap.run(primaryModuleType, args);
    }

    public static AppRuntime run(Module primaryModule, String... args) {
        return AppBootstrap.run(primaryModule, args);
    }
}
