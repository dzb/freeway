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
     * Container c = Freeway.create(OrderModule.class, CloudModule.class);
     * // or, when an instance carries configuration:
     * Container mixed = Freeway.create(
     *     ModuleDeclaration.of(new OrderModule("acme")),
     *     ModuleDeclaration.of(CloudModule.class));
     * }</pre>
     */
    public static Container create(ModuleEx... modules) {
        return ContainerImpl.of(declarations(modules));
    }

    /**
     * Creates the container over explicit declarations, so a composition can
     * mix class declarations and configured instances in one ordered list.
     */
    public static Container create(ModuleDeclaration... declarations) {
        return ContainerImpl.of(declarations);
    }

    /**
     * Creates the container over the given modules, with the composition root
     * named. The name is presentation only — it is what the startup log and
     * composition errors show as the application line — and it must not be
     * blank.
     *
     * <p>Both named forms exist — configured instances here, and
     * {@link ModuleDeclaration} values in the overload below. Pass at least one
     * module or declaration: a lone name is ambiguous because both overloads
     * accept an empty varargs list. Boot's chain also names a class-based
     * composition, {@code FreewayApp.create(OrderModule.class).name("order-service")}.
     *
     * <pre>{@code
     * Container c = Freeway.create("order-service", new OrderModule(), new CloudModule());
     * }</pre>
     */
    public static Container create(String appName, ModuleEx... modules) {
        return ContainerImpl.of(appName, declarations(modules));
    }

    /** Same, with an explicit mixed declaration list. */
    public static Container create(
            String appName, ModuleDeclaration... declarations) {
        return ContainerImpl.of(appName, declarations);
    }

    /** Creates the container over modules named by class — the normal form. */
    @SafeVarargs
    public static Container create(Class<? extends ModuleEx>... types) {
        if (types == null) {
            return ContainerImpl.of();
        }
        ModuleDeclaration[] declarations = new ModuleDeclaration[types.length];
        for (int i = 0; i < types.length; i++) {
            declarations[i] = ModuleDeclaration.of(
                java.util.Objects.requireNonNull(types[i], "module type"));
        }
        return ContainerImpl.of(declarations);
    }

    /** Converts an instance list to declarations; a null array is empty. */
    private static ModuleDeclaration[] declarations(ModuleEx... modules) {
        if (modules == null) {
            return new ModuleDeclaration[0];
        }
        ModuleDeclaration[] declarations = new ModuleDeclaration[modules.length];
        for (int i = 0; i < modules.length; i++) {
            declarations[i] = ModuleDeclaration.of(
                java.util.Objects.requireNonNull(modules[i], "module"));
        }
        return declarations;
    }
}
