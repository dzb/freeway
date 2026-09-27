package com.jujin.freeway.boot;

import com.jujin.freeway.boot.internal.AppRuntimeDefault;
import com.jujin.freeway.boot.internal.BootModule;
import com.jujin.freeway.boot.internal.ConfigLoaderImpl;
import com.jujin.freeway.commons.logging.LogBootstrap;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.ModuleEx;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of a Freeway application: place the modules, state what the
 * launch needs, start.
 *
 * <h3>Start now</h3>
 * <pre>{@code
 * AppRuntime app = FreewayApp.run(new MyModule());
 * AppRuntime app = FreewayApp.run(new String[]{"--freeway.profile=dev"}, new MyModule());
 * }</pre>
 *
 * <h3>Configure, then start</h3>
 * <pre>{@code
 * AppRuntime app = FreewayApp.create(new MyModule())
 *     .name("order-service")      // the root line in the startup log
 *     .add(CloudModule.class)     // module instances and class declarations mix here
 *     .args("--freeway.profile=dev")
 *     .autoDiscovery(false)       // no SPI gap-filling
 *     .shutdownHook(false)        // the caller owns the lifecycle
 *     .config(myConfig)           // a substituted AppConfig
 *     .start();
 * }</pre>
 *
 * <p>What {@code create(...)} returns is the application <em>before</em> it
 * runs: a launcher, single-use by design — a second {@code start()} would build
 * an independent container and register a second shutdown hook, so it fails
 * instead. The running application is the {@link AppRuntime} {@code start()}
 * returns.
 */
public final class FreewayApp {
    private static final Logger LOG = LoggerFactory.getLogger(FreewayApp.class);

    static {
        LogBootstrap.ensureProvider();
    }

    /**
     * The declared modules, in declaration order, each resolved when the container is built: a
     * class declaration is instantiated then (a declaration, not an early instance) and an
     * instance declaration reuses the module the caller configured.
     */
    private final List<Supplier<ModuleEx>> declared = new ArrayList<>();
    /** The composition root's name for the startup log; {@code null} = the default. */
    private String name;
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

    private FreewayApp() {
    }

    // ── entries ─────────────────────────────────────────────────

    /** Start an application with the given modules and empty arguments. */
    public static AppRuntime run(ModuleEx... modules) {
        return run(new String[0], modules);
    }

    /**
     * Start an application with the given modules and command-line arguments.
     * Config is loaded from the default cascade, ServiceLoader modules are
     * discovered, and a JVM shutdown hook is registered. Use
     * {@link #create(ModuleEx...)} for more control.
     */
    public static AppRuntime run(String[] args, ModuleEx... modules) {
        return create(modules).args(args).start();
    }

    /**
     * Start an application whose modules are named by class — the normal form;
     * an instance is for a module whose constructor takes arguments.
     */
    @SafeVarargs
    public static AppRuntime run(Class<? extends ModuleEx>... types) {
        return run(new String[0], types);
    }

    /** @see #run(Class[]) */
    @SafeVarargs
    public static AppRuntime run(String[] args, Class<? extends ModuleEx>... types) {
        return create(types).args(args).start();
    }

    /** The application with no modules yet — add them on the chain. */
    public static FreewayApp create() {
        return new FreewayApp();
    }

    /** The application over the given module instances. */
    public static FreewayApp create(ModuleEx... modules) {
        FreewayApp app = new FreewayApp();
        if (modules != null) {
            app.add(modules);
        }
        return app;
    }

    /** The application over modules named by class. */
    @SafeVarargs
    public static FreewayApp create(Class<? extends ModuleEx>... types) {
        return new FreewayApp().add(types);
    }

    // ── the chain ───────────────────────────────────────────────

    /** Add one or more modules to the application, in declaration order. */
    public FreewayApp add(ModuleEx... modules) {
        Objects.requireNonNull(modules, "modules");
        for (ModuleEx m : modules) {
            ModuleEx module = Objects.requireNonNull(m, "module");
            this.declared.add(() -> module);
        }
        return this;
    }

    /**
     * Add one or more modules named by class — the normal way; each is
     * instantiated through its no-arg constructor when the application starts.
     */
    @SafeVarargs
    public final FreewayApp add(Class<? extends ModuleEx>... types) {
        Objects.requireNonNull(types, "module types");
        for (Class<? extends ModuleEx> type : types) {
            Class<? extends ModuleEx> moduleType = Objects.requireNonNull(type, "module type");
            this.declared.add(() -> instantiate(moduleType));
        }
        return this;
    }

    /**
     * Instantiates a class declaration through its no-arg constructor, the same rule the container
     * applies to one (freeway-ioc, {@code ModuleNode.resolve}): a module whose constructor takes
     * arguments is declared as an instance. Boot instantiates here because the composition it hands
     * over mixes declared classes with the instances it owns ({@code BootModule}, SPI-discovered
     * modules), and only the instance-taking entry point can express that mix.
     */
    private static ModuleEx instantiate(Class<? extends ModuleEx> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.trySetAccessible();
            return constructor.newInstance();
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(
                "Module " + type.getName() + " has no no-arg constructor. A module whose"
                    + " constructor takes arguments is declared as an instance:"
                    + " .add(new " + type.getSimpleName() + "(…))", e);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("Cannot instantiate module " + type.getName(), cause);
        }
    }

    /** Set command-line arguments (used to override config values). */
    public FreewayApp args(String... args) {
        this.args = Objects.requireNonNull(args, "args");
        return this;
    }

    /**
     * Name the application for the startup log and composition errors — the root
     * line the loaded modules are shown under. Defaults to {@code application};
     * the name is presentation only and must not be blank.
     */
    public FreewayApp name(String name) {
        this.name = Objects.requireNonNull(name, "name");
        return this;
    }

    /**
     * Use a pre-built {@link AppConfig} instead of the default cascade —
     * the substitution point for custom config sources (remote servers,
     * other file formats): call
     * {@link com.jujin.freeway.boot.internal.AppConfigDefault#of(java.util.Map, java.util.List)}
     * or implement {@link AppConfig} yourself.
     */
    public FreewayApp config(AppConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        return this;
    }

    /**
     * Enable or disable ServiceLoader module discovery. On by default.
     * Set to {@code false} when you want only explicitly added modules.
     */
    public FreewayApp autoDiscovery(boolean enabled) {
        this.autoDiscovery = enabled;
        return this;
    }

    /** Use a specific class loader for resource lookup and SPI scanning. */
    public FreewayApp classLoader(ClassLoader loader) {
        this.classLoader = Objects.requireNonNull(loader, "classLoader");
        return this;
    }

    /**
     * Enable or disable automatic JVM shutdown-hook registration.
     * On by default. Set to {@code false} when you want to manage
     * the lifecycle yourself via {@link AppRuntime#close()}.
     */
    public FreewayApp shutdownHook(boolean enabled) {
        this.shutdownHook = enabled;
        return this;
    }

    // ── start ───────────────────────────────────────────────────

    /** Build and start the application. */
    public AppRuntime start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException(
                "FreewayApp.start() has already been called — a launcher is "
                    + "single-use (reuse would register a second shutdown "
                    + "hook and build an independent container)");
        }
        long startNanos = System.nanoTime();

        ClassLoader effectiveLoader = resolveClassLoader();
        AppConfig config = this.config != null
            ? this.config
            : ConfigLoaderImpl.load(effectiveLoader, args);

        Container container;
        AppRuntime app;
        try {
            // The composition is built inside the try because it can fail:
            // declaring the same module class twice, or an SPI provider that
            // cannot be instantiated, throws here. Those failures must release
            // the config exactly like a container failure does — the loader
            // already opened the hot-reload watcher (a thread plus a
            // WatchService), and a caller that catches and retries would leak
            // one per attempt. The composition — not a per-layer bookkeeping
            // map — is what decides duplicates, order and (for discovery) which
            // classes are already declared.
            List<ModuleEx> composed = new ArrayList<>(declared.size() + 1);
            composed.add(new BootModule(config)); // config first: later modules may read it
            for (Supplier<ModuleEx> declaration : declared) {
                composed.add(declaration.get());
            }
            if (autoDiscovery) {
                composed = ModuleDiscovery.fill(composed, effectiveLoader);
            }
            ModuleEx[] declarations = composed.toArray(ModuleEx[]::new);
            container = name != null
                ? Freeway.create(name, declarations)
                : Freeway.create(declarations);
            app = new AppRuntimeDefault(container, config);
        } catch (Throwable ex) {
            // No runtime hook will run, so release whatever the config holds
            // open (e.g. the hot-reload watcher).
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

    private ClassLoader resolveClassLoader() {
        if (classLoader != null) {
            return classLoader;
        }
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        return contextLoader != null ? contextLoader : FreewayApp.class.getClassLoader();
    }
}
