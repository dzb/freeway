package com.jujin.freeway.boot;

import com.jujin.freeway.commons.logging.LogBootstrap;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.ModuleNode;

/**
 * Entry point for building and starting a Freeway application.
 *
 * <h3>Minimal usage</h3>
 * <pre>{@code
 * AppRuntime app = FreewayApp.run(new MyModule());
 * AppRuntime app = FreewayApp.run(new String[]{"--freeway.profile=dev"}, new MyModule());
 * }</pre>
 *
 * <h3>Composed usage</h3>
 * <pre>{@code
 * // Grouping is part of the composition, not of a module: the tree is a value
 * // built here, named here, and handed to the container.
 * AppRuntime app = FreewayApp.run(ModuleNode.app("order-service",
 *     ModuleNode.of(new OrderModule()),
 *     ModuleNode.of(CloudModule.class)));
 * }</pre>
 *
 * <h3>Builder usage</h3>
 * <pre>{@code
 * AppRuntime app = FreewayApp.create(new MyModule())
 *     .args("--freeway.profile=dev")
 *     .start();
 *
 * AppRuntime app = FreewayApp.create()
 *     .add(new HttpModule())
 *     .add(new DbModule())
 *     .autoDiscovery(false)       // disable SPI scanning
 *     .shutdownHook(false)        // no JVM shutdown hook
 *     .config(myConfig)           // custom config source
 *     .start();
 * }</pre>
 */
public final class FreewayApp {

    static {
        LogBootstrap.ensureProvider();
    }

    private FreewayApp() {
    }

    /**
     * Start an application with the given modules and empty arguments.
     * Config is loaded from the default cascade, ServiceLoader modules
     * are discovered, and a JVM shutdown hook is registered.
     */
    /** Start an application with no modules and the given arguments. */
    public static AppRuntime run(String[] args) {
        return create().args(args).start();
    }

    public static AppRuntime run(ModuleEx... modules) {
        return run(new String[0], modules);
    }

    /**
     * Start an application with the given modules and command-line arguments.
     * Config is loaded from the default cascade, ServiceLoader modules
     * are discovered, and a JVM shutdown hook is registered.
     * Use {@link #create(ModuleEx...)} for more control.
     */
    public static AppRuntime run(String[] args, ModuleEx... modules) {
        return create(modules).args(args).start();
    }

    /**
     * Start an application from an explicitly composed module tree — the form
     * that expresses bundle placement and tree reuse. Discovery still fills the
     * gaps: a module class the tree already declares is not added again.
     */
    public static AppRuntime run(ModuleNode tree) {
        return run(new String[0], tree);
    }

    /** @see #run(ModuleNode) */
    public static AppRuntime run(String[] args, ModuleNode tree) {
        return create(tree).args(args).start();
    }

    /** Create an empty {@link AppBuilder}. */
    public static AppBuilder create() {
        return new AppBuilder();
    }

    /** Create an {@link AppBuilder} pre-populated with the given modules. */
    public static AppBuilder create(ModuleEx... modules) {
        AppBuilder b = new AppBuilder();
        if (modules != null) {
            b.add(modules);
        }
        return b;
    }

    /** Create an {@link AppBuilder} over a composed module tree. */
    public static AppBuilder create(ModuleNode tree) {
        return new AppBuilder().add(tree);
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

    /** Create an {@link AppBuilder} over modules named by class. */
    @SafeVarargs
    public static AppBuilder create(Class<? extends ModuleEx>... types) {
        return new AppBuilder().add(types);
    }
}
