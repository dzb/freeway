package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.internal.ContainerImpl;
import java.util.Arrays;
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

    static {
        com.jujin.freeway.commons.logging.LogBootstrap.ensureProvider();
    }

    private Freeway() {}

    public static Container create(ModuleEx... modules) {
        return create(modules == null ? List.of() : Arrays.asList(modules));
    }

    public static Container create(Collection<? extends ModuleEx> modules) {
        return new ContainerImpl(modules == null ? List.of() : modules);
    }
}
