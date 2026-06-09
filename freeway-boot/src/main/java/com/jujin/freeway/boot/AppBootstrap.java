package com.jujin.freeway.boot;

import com.jujin.freeway.boot.internal.BootConfig;
import com.jujin.freeway.boot.internal.BootConfigLoader;
import com.jujin.freeway.boot.internal.BootConfigLoader.BootConfigLayers;
import com.jujin.freeway.boot.internal.BootConfigModule;
import com.jujin.freeway.commons.logging.LoggingBootstrap;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

final class AppBootstrap {
    private static final Logger LOG = LoggerFactory.getLogger(AppBootstrap.class);

    static {
        LoggingBootstrap.autoConfigure();
    }

    private AppBootstrap() {
    }

    static AppRuntime run(Class<? extends Module> primaryModuleType, String... args) {
        Objects.requireNonNull(primaryModuleType, "primaryModuleType");
        return run(instantiate(primaryModuleType), args);
    }

    static AppRuntime run(Module primaryModule, String... args) {
        Objects.requireNonNull(primaryModule, "primaryModule");
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return start(loader, primaryModule, args);
    }

    private static AppRuntime start(ClassLoader loader, Module primaryModule, String... args) {
        long startNanos = System.nanoTime();
        ClassLoader effectiveLoader = loader != null ? loader : Launcher.class.getClassLoader();
        BootConfigLayers layers = BootConfigLoader.loadLayers(effectiveLoader, args);
        BootConfig config = new BootConfig(layers.merged(), layers.profiles());
        List<Module> modules = loadModules(primaryModule, effectiveLoader, config);

        LOG.info("Starting freeway application with {} module(s)", modules.size());
        Container container = Freeway.create(modules.toArray(Module[]::new));
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

    private static List<Module> loadModules(Module primaryModule, ClassLoader effectiveLoader, BootConfig config) {
        LinkedHashMap<Class<?>, Module> modules = new LinkedHashMap<>();
        modules.put(BootConfigModule.class, new BootConfigModule(config));
        modules.put(primaryModule.getClass(), primaryModule);
        for (Module module : ServiceLoader.load(Module.class, effectiveLoader)) {
            modules.putIfAbsent(module.getClass(), module);
        }
        return List.copyOf(modules.values());
    }

    private static Module instantiate(Class<? extends Module> moduleType) {
        try {
            return moduleType.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to instantiate module " + moduleType.getName(), ex);
        }
    }
}
