package com.jujin.freeway.boot;

import com.jujin.freeway.boot.internal.AppRuntimeDefault;
import com.jujin.freeway.boot.internal.BootModule;
import com.jujin.freeway.boot.internal.ConfigLoaderImpl;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.ModuleTree;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fluent builder for {@link FreewayApp}, created via {@link FreewayApp#of(ModuleEx...)}.
 *
 * <pre>{@code
 * AppRuntime app = FreewayApp.of(new MyModule())
 *     .args("--freeway.profile=dev")
 *     .autoDiscovery(false)
 *     .shutdownHook(false)
 *     .config(myConfig)
 *     .start();
 * }</pre>
 */
public final class AppBuilder {
    private static final Logger LOG = LoggerFactory.getLogger(AppBuilder.class);

    private final List<ModuleEx> modules = new ArrayList<>();
    private String[] args = new String[0];
    private AppConfig config;
    private boolean autoDiscovery = true;
    private boolean shutdownHook = true;
    private ClassLoader classLoader;
    // Single-use guard. AtomicBoolean (not a plain boolean) so that two
    // threads calling start() concurrently cannot both pass a check-then-set
    // race and build two containers / register two shutdown hooks: exactly
    // one compareAndSet wins, the other throws below.
    private final AtomicBoolean started = new AtomicBoolean();

    AppBuilder() {
    }

    /** Add one or more modules to the application. */
    public AppBuilder add(ModuleEx... modules) {
        Objects.requireNonNull(modules, "modules");
        for (ModuleEx m : modules) {
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

    /**
     * Use a pre-built {@link AppConfig} instead of the default cascade —
     * the substitution point for custom config sources (remote servers,
     * other file formats): call
     * {@link com.jujin.freeway.boot.internal.AppConfigDefault#of(java.util.Map, java.util.List)}
     * or implement {@link AppConfig} yourself.
     */
    public AppBuilder config(AppConfig config) {
        this.config = Objects.requireNonNull(config, "config");
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
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException(
                "AppBuilder.start() has already been called — a builder is "
                    + "single-use (reuse would register a second shutdown "
                    + "hook and build an independent container)");
        }
        long startNanos = System.nanoTime();

        ClassLoader effectiveLoader = resolveClassLoader();
        AppConfig config = this.config != null
            ? this.config
            : ConfigLoaderImpl.load(effectiveLoader, args);

        LinkedHashMap<Class<?>, ModuleEx> allModules = new LinkedHashMap<>();
        allModules.put(BootModule.class, new BootModule(config));
        for (ModuleEx module : modules) {
            addModule(allModules, module, true);
        }
        if (autoDiscovery) {
            // Discovery fills gaps. A module class already declared anywhere in
            // the tree — including as a sub-module of a bundle — is not added
            // again: the author's declaration wins, exactly as an explicitly
            // added module already wins over a discovered one.
            Set<Class<?>> declared = treeClasses(allModules.values());
            for (ModuleEx module : ServiceLoader.load(ModuleEx.class, effectiveLoader)) {
                try {
                    if (declared.contains(module.getClass())) {
                        LOG.debug(
                            "Ignoring discovered module already declared in the module "
                                + "tree: {}",
                            module.getClass().getSimpleName()
                        );
                        continue;
                    }
                    declared.add(module.getClass());
                    declared.addAll(treeClasses(List.of(module)));
                    addModule(allModules, module, false);
                } catch (ServiceConfigurationError ex) {
                    throw new IllegalStateException(
                        "Failed to load a ServiceLoader-discovered ModuleEx "
                            + "provider (classloader: " + effectiveLoader + ")",
                        ex
                    );
                }
            }
        }
        List<ModuleEx> moduleList = List.copyOf(allModules.values());

        Container container;
        AppRuntime app;
        try {
            container = Freeway.create(moduleList);
            app = new AppRuntimeDefault(container, config);
        } catch (Throwable ex) {
            // The container never came up, so no runtime hook will run: release
            // whatever the config holds open (e.g. the hot-reload watcher).
            try {
                config.close();
            } catch (RuntimeException closeFailure) {
                ex.addSuppressed(closeFailure);
            }
            throw ex;
        }
        Thread shutdownThread = null;

        if (shutdownHook) {
            shutdownThread = Thread.ofPlatform()
                .name("freeway-shutdown-hook")
                .unstarted(() -> {
                    try {
                        app.close();
                    } catch (Exception ex) {
                        LOG.warn("Error during shutdown", ex);
                    }
                });
            try {
                Runtime.getRuntime().addShutdownHook(shutdownThread);
            } catch (RuntimeException ex) {
                try {
                    app.close();
                } catch (RuntimeException closeFailure) {
                    ex.addSuppressed(closeFailure);
                }
                throw ex;
            }
        }

        try {
            app.start();
        } catch (Throwable ex) {
            if (shutdownThread != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownThread);
                } catch (RuntimeException ignored) {
                    // JVM is shutting down or hook removal is no longer possible.
                }
            }
            try {
                app.close();
            } catch (Throwable closeFailure) {
                ex.addSuppressed(closeFailure);
            }
            throw ex;
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        LOG.info("Started freeway application in {} ms", elapsedMs);
        return app;
    }

    private static void addModule(
        LinkedHashMap<Class<?>, ModuleEx> allModules,
        ModuleEx module,
        boolean explicit
    ) {
        ModuleEx existing = allModules.putIfAbsent(module.getClass(), module);
        if (existing == null) {
            return;
        }
        if (explicit) {
            if (existing == module) {
                // The identical instance was added twice (e.g. add(mod, mod))
                // — harmless, keep a single copy, mirroring the container's
                // identity-based collapse in ModuleTree.
                LOG.debug(
                    "Ignoring repeated module instance: {}",
                    module.getClass().getSimpleName()
                );
                return;
            }
            // Two distinct instances of the same class would silently drop
            // one module's configuration (e.g. add(new DbModule("ds1"), new
            // DbModule("ds2")) keeps only ds1). Fail fast instead.
            Class<?> moduleClass = module.getClass();
            if (moduleClass.isAnonymousClass() || moduleClass.isSynthetic()) {
                // Anonymous/lambda modules have no meaningful class identity —
                // keep identity-based semantics, like the tree resolver.
                LOG.debug(
                    "Ignoring duplicate module: {}",
                    moduleClass.getSimpleName()
                );
                return;
            }
            throw new IllegalStateException(
                "Module " + moduleClass.getName() + " added twice with "
                    + "two different instances. Likely cause: the same "
                    + "module class was added more than once explicitly "
                    + "(deduplication would silently drop one module's "
                    + "configuration). Fix: keep a single instance of each "
                    + "module class."
            );
        }
        // Explicit instances win over SPI-discovered ones — the explicit
        // module was already in the map, so the discovery copy is dropped.
        LOG.debug("Ignoring duplicate module: {}", module.getClass().getSimpleName());
    }

    /**
     * Every module class reachable in the tree of {@code modules}, sub-modules
     * included. Resolution also validates the tree, so an author-level
     * duplicate (the same class declared twice) fails here, before the
     * container is built.
     */
    private static Set<Class<?>> treeClasses(Collection<ModuleEx> modules) {
        Set<Class<?>> classes = new HashSet<>();
        for (ModuleEx module : ModuleTree.flatten(modules)) {
            classes.add(module.getClass());
        }
        return classes;
    }

    private ClassLoader resolveClassLoader() {
        if (classLoader != null) {
            return classLoader;
        }
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        return contextLoader != null ? contextLoader : AppBuilder.class.getClassLoader();
    }
}
