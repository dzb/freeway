package com.jujin.freeway.boot;

import com.jujin.freeway.boot.internal.BootConfig;
import com.jujin.freeway.boot.internal.BootConfigLoader;
import com.jujin.freeway.boot.internal.BootConfigLoader.BootConfigLayers;
import com.jujin.freeway.boot.internal.BootConfigModule;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.Module2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

public final class Launcher {
    private static final Logger LOG = LoggerFactory.getLogger(Launcher.class);

    private Launcher() {
    }

    public static AppRuntime run(String[] args, Module2... modules) {
        Objects.requireNonNull(modules, "modules");
        if (modules.length == 0) {
            throw new IllegalArgumentException("At least one module is required");
        }
        long startNanos = System.nanoTime();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        ClassLoader effectiveLoader = loader != null ? loader : Launcher.class.getClassLoader();
        BootConfigLayers layers = BootConfigLoader.loadLayers(effectiveLoader, args);
        BootConfig config = new BootConfig(layers.merged(), layers.profiles());

        LinkedHashMap<Class<?>, Module2> allModules = new LinkedHashMap<>();
        allModules.put(BootConfigModule.class, new BootConfigModule(config));
        for (Module2 module : modules) {
            allModules.putIfAbsent(module.getClass(), module);
        }
        for (Module2 module : ServiceLoader.load(Module2.class, effectiveLoader)) {
            allModules.putIfAbsent(module.getClass(), module);
        }
        List<Module2> moduleList = List.copyOf(allModules.values());

        Container container = Freeway.create(moduleList.toArray(Module2[]::new));
        AppRuntime app = new AppRuntimeDefault(container, config);

        try {
            app.start();
        } catch (RuntimeException ex) {
            try {
                app.close();
            } catch (RuntimeException closeFailure) {
                ex.addSuppressed(closeFailure);
            }
            throw ex;
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        LOG.info("Started freeway application in {} ms", elapsedMs);
        registerShutdownHook(app);
        return app;
    }

    private static void registerShutdownHook(AppRuntime app) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                app.close();
            } catch (Exception ex) {
                LOG.warn("Error during shutdown", ex);
            }
        }, "freeway-shutdown-hook"));
    }
}
