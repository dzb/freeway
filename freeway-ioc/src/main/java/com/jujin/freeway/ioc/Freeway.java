package com.jujin.freeway.ioc;

import com.jujin.freeway.commons.logging.LogBootstrap;
import com.jujin.freeway.ioc.internal.ContainerImpl;

/**
 * Entry point for creating a lightweight IoC {@link Container}.
 *
 * <p>Minimal example:
 * <pre>{@code
 * Container c = Freeway.create(binder -> {
 *     binder.bind(Greeter.class).to(GreeterImpl.class);
 * });
 * Greeter g = c.get(Greeter.class);
 * c.close();
 * }</pre>
 *
 * @see Container
 * @see ModuleEx
 */
public final class Freeway {

    // Ensure SLF4J is available before any container code runs a LoggerFactory.getLogger().
    static {
        LogBootstrap.ensureProvider();
    }

    private Freeway() {}

    /** Creates an empty container — no modules, so nothing is bound. */
    public static Container create() {
        return ContainerImpl.empty();
    }

    /**
     * Creates the container over the given modules. Grouping is declared by the
     * modules themselves ({@code @SubModule} turns a class into a bundle), never
     * by the caller assembling a structure:
     *
     * <pre>{@code
     * Container c = Freeway.create(new OrderModule(), CloudModule.class);
     * }</pre>
     */
    public static Container create(ModuleEx... modules) {
        return ContainerImpl.of(modules);
    }

    /**
     * Creates the container over the given modules, with the composition root
     * named. The name is presentation only — it is what the startup log and
     * composition errors show as the application line — and it must not be
     * blank.
     *
     * <p>Modules are instances here: a named class-declaring form would make a
     * lone name ambiguous between the two varargs overloads, so a class
     * declaration is passed as {@code new OrderModule()} — or named through
     * boot's chain, {@code FreewayApp.create(OrderModule.class).name("order-service")}.
     *
     * <pre>{@code
     * Container c = Freeway.create("order-service", new OrderModule(), new CloudModule());
     * }</pre>
     */
    public static Container create(String appName, ModuleEx... modules) {
        return ContainerImpl.of(appName, modules);
    }

    /** Creates the container over modules named by class — the normal form. */
    @SafeVarargs
    public static Container create(Class<? extends ModuleEx>... types) {
        return ContainerImpl.of(types);
    }
}
