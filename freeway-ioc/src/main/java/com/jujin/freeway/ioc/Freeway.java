package com.jujin.freeway.ioc;

import com.jujin.freeway.commons.logging.LogBootstrap;
import com.jujin.freeway.ioc.internal.ContainerImpl;
import java.util.Collection;
import java.util.List;

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
        return create(ModuleNode.app());
    }

    public static Container create(ModuleEx... modules) {
        return create(ModuleNode.app(modules == null ? new ModuleEx[0] : modules));
    }

    public static Container create(Collection<? extends ModuleEx> modules) {
        return create(ModuleNode.app(modules == null
            ? new ModuleEx[0] : modules.toArray(ModuleEx[]::new)));
    }

    /**
     * Creates the container over an explicitly composed module tree — the form
     * that expresses grouping and per-fragment reuse:
     *
     * <pre>{@code
     * Container c = Freeway.create(ModuleNode.app("orders",
     *     ModuleNode.leaf(new OrderModule()),
     *     CloudModules.standard()));
     * }</pre>
     */
    public static Container create(ModuleNode tree) {
        return new ContainerImpl(tree);
    }

    /** Creates the container over modules named by class — the normal form. */
    @SafeVarargs
    public static Container create(Class<? extends ModuleEx>... types) {
        return create(ModuleNode.app(types));
    }
}
