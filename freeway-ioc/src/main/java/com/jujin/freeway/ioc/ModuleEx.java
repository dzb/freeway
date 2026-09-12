package com.jujin.freeway.ioc;

/**
 * A module is the fundamental building block of a Freeway application.
 * Every module implements this interface and declares its bindings in
 * {@link #bind(Binder)}.
 *
 * <p>Modules are self-contained and declarative — they only declare what
 * should exist, without starting work during {@code bind()}. Actual
 * initialization happens when the container resolves services or when
 * {@link RuntimeHook#start(Container)} fires.
 *
 * <p><b>A module is a leaf.</b> It knows nothing about how the application is
 * composed: grouping lives in the composition itself, a {@link ModuleNode}
 * tree built at the entry point and handed to the container as a value.
 * Composition therefore has exactly one author (the assembly code), is visible
 * before anything binds, and cannot depend on {@code bind()} call order — the
 * container resolves the whole tree first, then binds it depth first, parents
 * before children and siblings in declaration order.
 *
 * <pre>{@code
 * ModuleNode app = ModuleNode.app("order-service",
 *     ModuleNode.leaf(new OrderModule()),
 *     CloudModules.standard());
 * FreewayApp.run(app);
 * }</pre>
 *
 * <p>A library that wants to ship several modules at once returns a
 * {@link ModuleNode} fragment from a factory (see {@code CloudModules}), which
 * is a plain value a caller can place, inspect or leave out — not a method the
 * framework calls back into.
 */
@FunctionalInterface
public interface ModuleEx {
    void bind(Binder binder);

    /**
     * The name this module is shown under — in the startup module tree and in
     * composition errors. Defaults to the simple class name; override it for
     * generated, anonymous or lambda modules whose class name says nothing.
     *
     * <p>Presentation only: it carries no identity, no ordering and no
     * contract about when it is called.
     */
    default String name() {
        return getClass().getSimpleName();
    }
}
