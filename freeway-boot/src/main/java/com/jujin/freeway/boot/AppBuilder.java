package com.jujin.freeway.boot;

import com.jujin.freeway.boot.internal.BootConfigLoader;
import com.jujin.freeway.boot.internal.BootConfigModule;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.Module2;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fluent builder for {@link FreewayApp}, created via {@link FreewayApp#of(Module2...)}.
 *
 * <pre>{@code
 * AppRuntime app = FreewayApp.of(new MyModule())
 *     .args("--freeway.profile=dev")
 *     .autoDiscovery(false)
 *     .shutdownHook(false)
 *     .config(myLoader)
 *     .start();
 * }</pre>
 */
public final class AppBuilder {
    private static final Logger LOG = LoggerFactory.getLogger(AppBuilder.class);

    private final List<Module2> modules = new ArrayList<>();
    private String[] args = new String[0];
    private ConfigLoader configLoader;
    private boolean autoDiscovery = true;
    private boolean shutdownHook = true;
    private ClassLoader classLoader;

    AppBuilder() {
    }

    /** Add one or more modules to the application. */
    public AppBuilder add(Module2... modules) {
        Objects.requireNonNull(modules, "modules");
        for (Module2 m : modules) {
            Objects.requireNonNull(m, "module");
            this.modules.add(m);
        }
        return this;
    }

    /** Set command-line arguments (used to override config values). */
    public AppBuilder args(String... args) {
        this.args = Objects.requireNonNull(args, "args");
        return this;
    }

    /** Use a custom {@link ConfigLoader} instead of the default cascade. */
    public AppBuilder config(ConfigLoader loader) {
        this.configLoader = Objects.requireNonNull(loader, "configLoader");
        return this;
    }

    /**
     * Enable or disable ServiceLoader module discovery. On by default.
     * Set to {@code false} when you want only explicitly added modules.
     */
    public AppBuilder autoDiscovery(boolean enabled) {
        this.autoDiscovery = enabled;
        return this;
    }

    /** Use a specific class loader for resource lookup and SPI scanning. */
    public AppBuilder classLoader(ClassLoader loader) {
        this.classLoader = Objects.requireNonNull(loader, "classLoader");
        return this;
    }

    /**
     * Enable or disable automatic JVM shutdown-hook registration.
     * On by default. Set to {@code false} when you want to manage
     * the lifecycle yourself via {@link AppRuntime#close()}.
     */
    public AppBuilder shutdownHook(boolean enabled) {
        this.shutdownHook = enabled;
        return this;
    }

    /** Build and start the application. */
    public AppRuntime start() {
        if (modules.isEmpty()) {
            throw new IllegalArgumentException("At least one module is required");
        }
        long startNanos = System.nanoTime();

        ClassLoader effectiveLoader = resolveClassLoader();
        ConfigLoader effectiveConfigLoader = configLoader != null
            ? configLoader
            : new BootConfigLoader();
        AppConfig config = effectiveConfigLoader.load(effectiveLoader, args);

        LinkedHashMap<Class<?>, Module2> allModules = new LinkedHashMap<>();
        allModules.put(BootConfigModule.class, new BootConfigModule(config));
        for (Module2 module : modules) {
            addModule(allModules, module);
        }
        if (autoDiscovery) {
            for (Module2 module : ServiceLoader.load(Module2.class, effectiveLoader)) {
                addModule(allModules, module);
            }
        }
        List<Module2> moduleList = List.copyOf(allModules.values());

        Container container = Freeway.create(moduleList);
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

        if (shutdownHook) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    app.close();
                } catch (Exception ex) {
                    LOG.warn("Error during shutdown", ex);
                }
            }, "freeway-shutdown-hook"));
        }
        return app;
    }

    private static void addModule(
        LinkedHashMap<Class<?>, Module2> allModules,
        Module2 module
    ) {
        Module2 existing = allModules.putIfAbsent(module.getClass(), module);
        if (existing == null) {
            return;
        }
        LOG.debug("Ignoring duplicate module: {}", module.getClass().getSimpleName());
    }

    private ClassLoader resolveClassLoader() {
        if (classLoader != null) {
            return classLoader;
        }
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        return contextLoader != null ? contextLoader : AppBuilder.class.getClassLoader();
    }
}
