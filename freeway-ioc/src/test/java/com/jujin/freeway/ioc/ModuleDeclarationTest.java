package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.annotation.SubModule;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleDeclarationTest {

    static final class Leaf implements ModuleEx {
        @Override public void bind(Binder binder) {}
    }

    @SubModule(Leaf.class)
    static final class Bundle implements ModuleEx {
        @Override public void bind(Binder binder) {}
    }

    static final class Other implements ModuleEx {
        @Override public void bind(Binder binder) {}
    }

    static final class Counting implements ModuleEx {
        static final AtomicInteger constructions = new AtomicInteger();

        Counting() {
            constructions.incrementAndGet();
        }

        @Override public void bind(Binder binder) {}
    }

    static final class NeedsArguments implements ModuleEx {
        NeedsArguments(String ignored) {}

        @Override public void bind(Binder binder) {}
    }

    @Test
    void classDeclarationResolvesAFreshInstanceOnlyWhenAsked() {
        Counting.constructions.set(0);
        ModuleDeclaration declaration = ModuleDeclaration.of(Counting.class);

        assertEquals(0, Counting.constructions.get());
        ModuleEx first = declaration.resolve();
        ModuleEx second = declaration.resolve();

        assertNotSame(first, second, "each resolution is a fresh class instance");
        assertEquals(2, Counting.constructions.get());
    }

    @Test
    void instanceDeclarationReusesTheConfiguredInstance() {
        Leaf instance = new Leaf();
        ModuleDeclaration declaration = ModuleDeclaration.of(instance);

        assertTrue(declaration.isInstance());
        assertSame(instance, declaration.resolve());
        assertSame(instance, declaration.resolve());
        assertEquals(Leaf.class, declaration.type());
    }

    @Test
    void classDeclarationNamesTheInstanceFormWhenConstructionIsImpossible() {
        ModuleDeclaration declaration = ModuleDeclaration.of(NeedsArguments.class);

        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class, declaration::resolve);

        assertTrue(failure.getMessage().contains(NeedsArguments.class.getName()));
        assertTrue(failure.getMessage().contains("declared as an instance"));
        assertTrue(failure.getMessage().contains("new NeedsArguments"));
    }

    @Test
    void submodulesAreTheStaticAnnotationMetadata() {
        assertEquals(List.of(Leaf.class), ModuleDeclaration.of(Bundle.class).submodules());
        assertEquals(List.of(), ModuleDeclaration.of(Leaf.class).submodules());
    }

    @Test
    void namelessInstancesAreComparedByIdentityRatherThanType() {
        ModuleEx lambda = binder -> {};

        assertFalse(ModuleDeclaration.of(lambda).hasStableType());
        assertTrue(ModuleDeclaration.of(Leaf.class).hasStableType());
    }

    @Test
    void mixedDeclarationsComposeInOneContainer() {
        Leaf instance = new Leaf();
        try (Container container = Freeway.create(
                ModuleDeclaration.of(instance),
                ModuleDeclaration.of(Other.class))) {
            assertInstanceOf(Container.class, container);
        } catch (RuntimeException ex) {
            throw ex;
        }
    }
}
