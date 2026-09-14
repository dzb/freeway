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
 * <p><b>A module is placed whole.</b> How the application is composed lives in
 * the composition itself, a {@link ModuleNode} tree built at the entry point
 * and handed to the container as a value. A module that ships a group of
 * modules is a bundle: it declares them with {@link
 * com.jujin.freeway.ioc.annotation.SubModule}, static metadata read while the
 * tree is built, and placing the bundle places them. A submodule is an
 * ordinary module and may always be placed on its own instead — the entry point
 * decides. Composition therefore has exactly one author (the assembly code) and
 * is visible before anything binds — the container resolves the whole tree
 * first (a class-declared module is instantiated at load, not at composition),
 * then binds its modules in pre-order: the application root binds nothing, a
 * module binds before the modules below it, and siblings bind in declaration
 * order.
 *
 * <pre>{@code
 * ModuleNode app = ModuleNode.app("order-service",
 *     ModuleNode.of(new OrderModule()),
 *     ModuleNode.of(CloudModule.class));    // a bundle
 * FreewayApp.run(app);
 * }</pre>
 *
 * <p>A library that ships several modules declares them on the bundle class,
 * not in a factory method the framework calls back into: the declaration is
 * data, the caller places it.
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
