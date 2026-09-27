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
 * the composition itself: the modules handed to the entry point, in the order
 * they are given. A module that ships a group of modules is a bundle: it
 * declares them with {@link com.jujin.freeway.ioc.annotation.SubModule},
 * static metadata read while the composition is assembled, and placing the
 * bundle places them. A submodule is an ordinary module and may always be
 * placed on its own instead — the caller decides. Composition therefore has
 * exactly one author (the assembly code) and is visible before anything binds —
 * the container resolves the whole composition first (a class-declared module
 * is instantiated then, not while the call is written), then binds its modules
 * in pre-order: a module binds before the modules it bundles, and siblings bind
 * in declaration order.
 *
 * <pre>{@code
 * FreewayApp.run(new OrderModule(), CloudModule.class);   // the bundle expands
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
     * The name this module is shown under — in the startup log and in
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
