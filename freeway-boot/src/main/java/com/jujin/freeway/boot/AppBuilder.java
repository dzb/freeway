package com.jujin.freeway.boot;

import com.jujin.freeway.boot.internal.AppRuntimeDefault;
import com.jujin.freeway.boot.internal.BootModule;
import com.jujin.freeway.boot.internal.ConfigLoaderImpl;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.ModuleNode;
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

    /** The application node's name — what the startup log shows as its root line. */
    private static final String APP_NAME = "application";

    /** The application's children: one node per added module or fragment. */
    private final List<ModuleNode> children = new ArrayList<>();
    /** The application node the caller already composed, if any. */
    private ModuleNode applicationTree;
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

    /** Add one or more modules to the application — each becomes a leaf of the tree. */
    public AppBuilder add(ModuleEx... modules) {
        Objects.requireNonNull(modules, "modules");
        for (ModuleEx m : modules) {
            this.children.add(ModuleNode.leaf(Objects.requireNonNull(m, "module")));
        }
        return this;
    }

    /**
     * Add one or more composed fragments: a fragment is a {@link ModuleNode}
     * built with {@code app/leaf/of}, so grouping and reuse are expressed here
     * rather than inside a module.
     */
    public AppBuilder add(ModuleNode... trees) {
        Objects.requireNonNull(trees, "trees");
        for (ModuleNode tree : trees) {
            ModuleNode node = Objects.requireNonNull(tree, "tree");
            if (node.isApplication() && applicationTree == null) {
                // The caller composed the application root: reuse it (name and
                // children) instead of nesting a second root around it.
                applicationTree = node;
            } else {
                this.children.add(node);
            }
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

        // The composition is built once, here: the application node with every
        // added module or fragment, plus whatever SPI discovery fills in. The
        // tree — not a per-layer bookkeeping map — is what decides duplicates,
        // order and (for discovery) which classes are already declared.
        List<ModuleNode> composed = new ArrayList<>();
        String appName = APP_NAME;
        if (applicationTree != null) {
            appName = applicationTree.name();
            applicationTree.children().forEach(child -> composed.add(ModuleNode.of(child)));
        }
        composed.add(ModuleNode.leaf(new BootModule(config)));
        composed.addAll(children);
        ModuleNode tree = ModuleNode.app(appName, composed.toArray(ModuleNode[]::new));
        if (autoDiscovery) {
            tree = discover(tree, effectiveLoader, composed);
        }

        Container container;
        AppRuntime app;
        try {
            container = Freeway.create(tree);
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

    /**
     * Fills the gaps with ServiceLoader-discovered modules: a class the tree
     * already declares — anywhere, fragments included — is not added again, so
     * an author's declaration always wins over discovery.
     */
    private static ModuleNode discover(
        ModuleNode tree, ClassLoader loader, List<ModuleNode> composed
    ) {
        String appName = tree.name();
        Set<Class<?>> declared = new HashSet<>(tree.classes());
        List<ModuleNode> discovered = new ArrayList<>();
        for (ModuleEx module : ServiceLoader.load(ModuleEx.class, loader)) {
            try {
                if (!declared.add(module.getClass())) {
                    LOG.debug("Ignoring discovered module already declared in the module "
                            + "tree: {}", module.getClass().getSimpleName());
                    continue;
                }
                discovered.add(ModuleNode.leaf(module));
            } catch (ServiceConfigurationError ex) {
                throw new IllegalStateException(
                    "Failed to load a ServiceLoader-discovered ModuleEx provider (classloader: "
                        + loader + ")", ex);
            }
        }
        if (discovered.isEmpty()) {
            return tree;
        }
        List<ModuleNode> all = new ArrayList<>(composed);
        all.addAll(discovered);
        return ModuleNode.app(appName, all.toArray(ModuleNode[]::new));
    }

    private ClassLoader resolveClassLoader() {
        if (classLoader != null) {
            return classLoader;
        }
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        return contextLoader != null ? contextLoader : AppBuilder.class.getClassLoader();
    }
}
