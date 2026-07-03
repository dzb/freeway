package com.jujin.freeway.boot;

import com.jujin.freeway.ioc.ModuleEx;

/**
 * Entry point for building and starting a Freeway application.
 *
 * <h3>Minimal usage</h3>
 * <pre>{@code
 * AppRuntime app = FreewayApp.run(new MyModule());
 * AppRuntime app = FreewayApp.run(new String[]{"--freeway.profile=dev"}, new MyModule());
 * }</pre>
 *
 * <h3>Builder usage</h3>
 * <pre>{@code
 * AppRuntime app = FreewayApp.of(new MyModule())
 *     .args("--freeway.profile=dev")
 *     .start();
 *
 * AppRuntime app = FreewayApp.of()
 *     .add(new HttpModule())
 *     .add(new DbModule())
 *     .autoDiscovery(false)       // disable SPI scanning
 *     .shutdownHook(false)        // no JVM shutdown hook
 *     .config(myLoader)           // custom config source
 *     .start();
 * }</pre>
 */
public final class FreewayApp {

    static {
        com.jujin.freeway.commons.logging.LogBootstrap.ensureProvider();
    }

    private FreewayApp() {
    }

    /**
     * Start an application with the given modules and empty arguments.
     * Config is loaded from the default cascade, ServiceLoader modules
     * are discovered, and a JVM shutdown hook is registered.
     */
    public static AppRuntime run(ModuleEx... modules) {
        return run(new String[0], modules);
    }

    /**
     * Start an application with the given modules and command-line arguments.
     * Config is loaded from the default cascade, ServiceLoader modules
     * are discovered, and a JVM shutdown hook is registered.
     * Use {@link #of(ModuleEx...)} for more control.
     */
    public static AppRuntime run(String[] args, ModuleEx... modules) {
        return of(modules).args(args).start();
    }

    /** Create an {@link AppBuilder} pre-populated with the given modules. */
    public static AppBuilder of(ModuleEx... modules) {
        AppBuilder b = new AppBuilder();
        if (modules != null) {
            b.add(modules);
        }
        return b;
    }
}
