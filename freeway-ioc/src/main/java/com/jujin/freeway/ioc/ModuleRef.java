package com.jujin.freeway.ioc;

import java.util.Objects;

/**
 * A module declaration in the composition tree: a class to instantiate when
 * loading starts, or a configured instance already present at composition.
 *
 * <p>Both forms are one declaration concept: the container binds
 * {@link #resolve()} for every leaf in {@link ModuleNode#bindOrder()}, so
 * module code cannot tell how it was declared. The difference is only when
 * construction happens — a {@link OfClass} is created at load time, while an
 * {@link OfInstance} (including a lambda or anonymous module, whose captured
 * state cannot be recreated) is created where the application assembles it.
 *
 * <p>A class-declared module is instantiated through its no-arg constructor.
 * A module whose constructor takes arguments is declared as an instance:
 * {@code ModuleNode.leaf(new TenantModule("acme"))}.
 */
public sealed interface ModuleRef permits ModuleRef.OfClass, ModuleRef.OfInstance {

    /** The module type — known without instantiating. */
    Class<? extends ModuleEx> type();

    /**
     * The name this declaration is shown under: the class's simple name for a
     * class declaration, {@link ModuleEx#name()} for an instance.
     */
    default String name() {
        return type().getSimpleName();
    }

    /**
     * The module to bind: a fresh no-arg instance for a class declaration, the
     * declared instance otherwise. Called once per leaf per load, so a class
     * declaration yields a new module for every container it is loaded into.
     */
    ModuleEx resolve();

    /** A module to instantiate when loading starts. */
    static ModuleRef of(Class<? extends ModuleEx> type) {
        return new OfClass(type);
    }

    /** A configured module instance. */
    static ModuleRef of(ModuleEx instance) {
        return new OfInstance(instance);
    }

    /** A module declared by class: constructed by the container at load time. */
    record OfClass(Class<? extends ModuleEx> type) implements ModuleRef {

        public OfClass {
            Objects.requireNonNull(type, "type");
        }

        @Override
        public ModuleEx resolve() {
            try {
                var constructor = type.getDeclaredConstructor();
                constructor.trySetAccessible();
                return constructor.newInstance();
            } catch (NoSuchMethodException e) {
                throw new IllegalArgumentException(
                    "Module " + type.getName() + " has no no-arg constructor. A module whose"
                        + " constructor takes arguments is declared as an instance: ModuleNode"
                        + ".leaf(new " + type.getSimpleName() + "(…))", e);
            } catch (ReflectiveOperationException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new IllegalStateException(
                    "Cannot instantiate module " + type.getName() + ": " + cause, cause);
            }
        }
    }

    /**
     * A module declared as an instance — the form for constructor arguments,
     * and the only form a lambda or anonymous module can take.
     */
    record OfInstance(ModuleEx instance) implements ModuleRef {

        public OfInstance {
            Objects.requireNonNull(instance, "instance");
        }

        @Override
        public Class<? extends ModuleEx> type() {
            return instance.getClass();
        }

        @Override
        public String name() {
            return instance.name();
        }

        @Override
        public ModuleEx resolve() {
            return instance;
        }
    }
}
