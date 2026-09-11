package com.jujin.freeway.ioc;

import java.util.List;

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
 * <p><b>Composition is data, and it forms a tree.</b> A module that groups
 * others (an umbrella such as a cloud or web bundle) returns them from
 * {@link #subModules()} instead of installing them while binding. The
 * container resolves that tree before binding anything, so the module set is
 * knowable up front instead of emerging as a side effect of {@code bind()}
 * call order: a module binds before its sub-modules, siblings in declaration
 * order.
 *
 * <p>Example:
 * <pre>{@code
 * public class WebBundle implements ModuleEx {
 *     public List<ModuleEx> subModules() {
 *         return List.of(new HttpModule(), new DbModule());
 *     }
 *     public void bind(Binder b) {
 *         b.bind(UserService.class).to(UserServiceImpl.class);
 *     }
 * }
 * }</pre>
 */
@FunctionalInterface
public interface ModuleEx {
    void bind(Binder binder);

    /**
     * The modules this one is composed of, in the order they should bind —
     * a <em>view</em> of the composition, not a factory. The container
     * resolves the tree before binding: this module first, then its
     * sub-modules depth-first, siblings in the order returned here.
     *
     * <p><b>Declare it once.</b> Return the same instances on every call,
     * normally by exposing a field:
     * <pre>{@code
     * private final List<ModuleEx> subModules = List.of(new HttpModule(), new DbModule());
     *
     * @Override
     * public List<ModuleEx> subModules() {
     *     return subModules;
     * }
     * }</pre>
     * The framework reads this more than once per startup — the application
     * entry point, to skip modules already declared here when it applies SPI
     * discovery, and the container, to resolve the tree. A method that builds a
     * fresh list each time still starts correctly, but it makes the module set
     * unobservable: {@code container.modules()} would report one set of
     * instances while a caller reading {@code subModules()} gets another.
     *
     * <p>Deduplication applies to the whole tree, not just the modules handed
     * to the application entry point: two distinct instances of one module
     * class fail startup. A sub-module declared by an umbrella and also added
     * explicitly is therefore the same mistake — and now the same error. The
     * same <em>instance</em> reached twice (a shared sub-module, or a mutual
     * reference) binds once: that is not a supported shape, just a defensive
     * collapse so a mistake cannot recurse forever or double-bind.
     *
     * <p>Default: none (a leaf module).
     */
    default List<ModuleEx> subModules() {
        return List.of();
    }
}
