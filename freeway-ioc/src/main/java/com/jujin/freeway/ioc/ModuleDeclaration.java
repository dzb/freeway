package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.annotation.SubModule;

import java.util.List;
import java.util.Objects;

/**
 * One placed module in a composition: either a class declaration resolved when
 * a container loads, or an already-configured module instance.
 *
 * <p>This is the value that lets a composition mix both forms — and the one
 * owner of the class-declaration rules that every launcher needs. A class is
 * instantiated through its no-arg constructor only when a container resolves
 * the declaration; a module whose constructor takes arguments is placed as an
 * instance. {@link #submodules()} is the same static {@link SubModule} metadata
 * the composition tree expands, so discovery and loading cannot disagree about
 * what a bundle contains.</p>
 *
 * <p>Most applications keep using the convenience entry points
 * ({@code Freeway.create(new MyModule())},
 * {@code Freeway.create(MyModule.class)}, {@code FreewayApp.create(...)}).
 * {@code ModuleDeclaration} is the explicit form for a mixed list or a
 * launcher that carries declarations forward without instantiating them.</p>
 */
public sealed interface ModuleDeclaration
        permits ModuleDeclaration.ClassDeclaration,
                ModuleDeclaration.InstanceDeclaration {

    /** The declared class, or the concrete type of the instance. */
    Class<? extends ModuleEx> type();

    /**
     * The module to bind: a fresh no-arg instance for a class declaration, the
     * configured instance otherwise.
     *
     * @throws IllegalArgumentException when a class declaration has no no-arg
     *         constructor, with the instance-form fix in the message
     */
    ModuleEx resolve();

    /** True when the caller placed a configured instance, false for a class. */
    boolean isInstance();

    /** Name used in composition logs and errors. */
    String name();

    /** The immediate classes this module bundles through {@link SubModule}. */
    List<Class<? extends ModuleEx>> submodules();

    /**
     * True when the class is a meaningful declaration identity. Anonymous and
     * synthetic classes (lambdas, test doubles) are compared by instance
     * identity only; a class declaration of a normal module is compared by
     * class, and declaring it twice is an error.
     */
    default boolean hasStableType() {
        return !type().isAnonymousClass() && !type().isSynthetic();
    }

    /** Declares a module class, resolved when a container loads it. */
    static ModuleDeclaration of(Class<? extends ModuleEx> type) {
        return new ClassDeclaration(type);
    }

    /** Places an already-configured module instance. */
    static ModuleDeclaration of(ModuleEx module) {
        return new InstanceDeclaration(module);
    }

    /** A module class resolved through its no-arg constructor at load time. */
    record ClassDeclaration(Class<? extends ModuleEx> type) implements ModuleDeclaration {

        public ClassDeclaration {
            Objects.requireNonNull(type, "module type");
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
                        + " constructor takes arguments is declared as an instance: new "
                        + type.getSimpleName() + "(…)", e);
            } catch (ReflectiveOperationException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new IllegalStateException(
                    "Cannot instantiate module " + type.getName() + ": " + cause, cause);
            }
        }

        @Override
        public boolean isInstance() {
            return false;
        }

        @Override
        public String name() {
            return type.getSimpleName();
        }

        @Override
        public List<Class<? extends ModuleEx>> submodules() {
            return ModuleDeclaration.submodulesOf(type);
        }
    }

    /** A configured module instance placed into a composition. */
    record InstanceDeclaration(ModuleEx module) implements ModuleDeclaration {

        public InstanceDeclaration {
            Objects.requireNonNull(module, "module");
        }

        @Override
        public Class<? extends ModuleEx> type() {
            return module.getClass();
        }

        @Override
        public ModuleEx resolve() {
            return module;
        }

        @Override
        public boolean isInstance() {
            return true;
        }

        @Override
        public String name() {
            return module.name();
        }

        @Override
        public List<Class<? extends ModuleEx>> submodules() {
            return ModuleDeclaration.submodulesOf(module.getClass());
        }
    }

    private static List<Class<? extends ModuleEx>> submodulesOf(
            Class<? extends ModuleEx> type) {
        SubModule bundle = type.getAnnotation(SubModule.class);
        return bundle == null || bundle.value().length == 0
            ? List.of()
            : List.of(bundle.value());
    }
}
