package com.jujin.freeway.boot;

import com.jujin.freeway.boot.internal.BootConfigLoader;
import com.jujin.freeway.boot.internal.BootConfigLoader.BootConfigLayers;
import com.jujin.freeway.boot.internal.BootConfig;
import com.jujin.freeway.boot.internal.BootConfigModule;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.Module;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Launcher {
    private static final Logger LOG = LoggerFactory.getLogger(Launcher.class);

    private Launcher() {
    }

    public static App run(Class<? extends Module> primaryModuleType, String... args) {
        Objects.requireNonNull(primaryModuleType, "primaryModuleType");
        return run(instantiate(primaryModuleType), args);
    }

    public static App run(Module primaryModule, String... args) {
        Objects.requireNonNull(primaryModule, "primaryModule");
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return start(loader, primaryModule, args);
    }

    private static App start(ClassLoader loader, Module primaryModule, String... args) {
        long startNanos = System.nanoTime();
        ClassLoader effectiveLoader = loader != null ? loader : Launcher.class.getClassLoader();
        BootConfigLayers layers = BootConfigLoader.loadLayers(effectiveLoader, args);
        List<Module> modules = loadModules(primaryModule, effectiveLoader, layers);

        LOG.info("Starting freeway application with {} module(s)", modules.size());
        Container container = Freeway.create(modules.toArray(Module[]::new));
        App app = new AppImpl(container, new BootConfig(layers.merged(), layers.profiles()));

        registerShutdownHook(app);

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        LOG.info("Started freeway application in {} ms", elapsedMs);
        return app;
    }

    private static void registerShutdownHook(App app) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                app.close();
            } catch (Exception ex) {
                LOG.warn("Error during shutdown", ex);
            }
        }, "freeway-shutdown-hook"));
    }

    private static List<Module> loadModules(Module primaryModule, ClassLoader effectiveLoader, BootConfigLayers layers) {
        LinkedHashMap<Class<?>, Module> modules = new LinkedHashMap<>();
        modules.put(BootConfigModule.class, new BootConfigModule(layers.merged()));
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
