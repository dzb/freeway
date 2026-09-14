package com.jujin.freeway.ioc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.ioc.annotation.SubModule;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The module tree is a value built at the entry point: the container binds its
 * module nodes in pre-order, bundles bring the submodules they declare, and
 * construction refuses a tree that names a module twice.
 */
class ModuleNodeTest {

    /** Binds its name into {@code log}; anonymous, so it has no class identity. */
    private static ModuleEx named(String name, List<String> log) {
        return new ModuleEx() {
            @Override
            public void bind(Binder binder) {
                log.add(name);
            }

            @Override
            public String name() {
                return name;
            }
        };
    }

    /** A named module class — subject to the one-declaration-per-class rule. */
    static final class Marker implements ModuleEx {
        @Override
        public void bind(Binder binder) {
            binder.bind(Marker.class).to(c -> new Marker());
        }
    }

    static final class Other implements ModuleEx {
        @Override
        public void bind(Binder binder) {
            binder.bind(Other.class).to(c -> new Other());
        }
    }

    /** A submodule of {@link WebBundle}, to pin pre-order and rendering. */
    static final class Middle implements ModuleEx {
        @Override
        public void bind(Binder binder) {
            binder.bind(Middle.class).to(c -> new Middle());
        }
    }

    /** A bundle: a module whose class declares its submodules. */
    @SubModule(Middle.class)
    static final class WebBundle implements ModuleEx {
        @Override
        public void bind(Binder binder) {
        }
    }

    /** A bundle over {@link Marker}, to pin duplicate paths through a bundle. */
    @SubModule(Marker.class)
    static final class MarkerBundle implements ModuleEx {
        @Override
        public void bind(Binder binder) {
        }
    }

    /** Cycle fixture: {@link CycleA} declares {@link CycleB} and vice versa. */
    @SubModule(CycleB.class)
    static final class CycleA implements ModuleEx {
        @Override
        public void bind(Binder binder) {
        }
    }

    @SubModule(CycleA.class)
    static final class CycleB implements ModuleEx {
        @Override
        public void bind(Binder binder) {
        }
    }

    /** A module that must be declared as an instance: no no-arg constructor. */
    static final class NeedsArguments implements ModuleEx {
        NeedsArguments(String label) {
        }

        @Override
        public void bind(Binder binder) {
        }
    }

    /** Counts constructions and binds itself, to observe when a class resolves. */
    static final class CountingModule implements ModuleEx {
        static final AtomicInteger constructions = new AtomicInteger();

        CountingModule() {
            constructions.incrementAndGet();
        }

        @Override
        public void bind(Binder binder) {
            binder.bind(CountingModule.class).to(c -> this);
        }
    }

    /** The names of every node, pre-order. */
    private static List<String> nodeNames(ModuleNode root) {
        List<String> names = new ArrayList<>();
        Deque<ModuleNode> pending = new ArrayDeque<>(List.of(root));
        while (!pending.isEmpty()) {
            ModuleNode node = pending.pop();
            names.add(node.name());
            List<ModuleNode> children = node.children();
            for (int i = children.size() - 1; i >= 0; i--) {
                pending.push(children.get(i));
            }
        }
        return names;
    }

    private static List<String> bindNames(ModuleNode root) {
        return root.bindOrder().stream().map(ModuleNode::name).toList();
    }

    // ── shape, bundles and order ────────────────────────────────

    @Test
    void appNodeNamesTheRootAndBindsNothing() {
        List<String> log = new ArrayList<>();
        ModuleNode app = ModuleNode.app("orders", ModuleNode.of(named("a", log)));

        assertEquals("orders", app.name());
        assertEquals(2, app.size());
        assertEquals(List.of("orders", "a"), nodeNames(app),
            "the application root is the first line of the tree");
        try (Container container = Freeway.create(app)) {
            assertEquals(List.of("a"), log, "the structural root declares no bindings");
            assertSame(app, container.moduleTree());
        }
    }

    @Test
    void bundlePlacesItsSubModulesAfterItInDeclarationOrder() {
        ModuleNode tree = ModuleNode.app("test", ModuleNode.of(WebBundle.class));

        assertEquals(List.of("test", "WebBundle", "Middle"), nodeNames(tree));
        assertEquals(3, tree.size(), "root + bundle + submodule");
        try (Container container = Freeway.create(tree)) {
            assertIterableEquals(List.of("WebBundle", "Middle"), bindNames(container.moduleTree()),
                "a bundle binds before the submodules it declares");
            assertNotNull(container.get(Middle.class));
        }
    }

    @Test
    void renderShowsThePreOrderStructure() {
        ModuleNode tree = ModuleNode.app("test",
            ModuleNode.of(WebBundle.class),
            ModuleNode.of(new Other()));

        assertEquals("- test\n  - WebBundle\n    - Middle\n  - Other", tree.render());
    }

    @Test
    void treeValueExposesShapeAndIsImmutable() {
        ModuleNode tree = ModuleNode.app("test",
            ModuleNode.of(Marker.class),
            ModuleNode.of(WebBundle.class));

        assertEquals("test", tree.name());
        assertEquals(4, tree.size());
        assertTrue(tree.isApplication());
        assertNull(tree.type(), "the application root declares no module");
        assertEquals(2, tree.children().size());

        ModuleNode node = tree.children().get(0);
        assertNotNull(node.type(), "a module node carries its declaration");
        assertNull(node.instance(), "a class declaration carries no instance");
        assertTrue(node.children().isEmpty());

        ModuleNode bundle = tree.children().get(1);
        assertEquals("WebBundle", bundle.name());
        assertEquals(WebBundle.class, bundle.type());
        assertEquals(1, bundle.children().size(), "a bundle carries its submodules");
        assertFalse(bundle.isApplication());

        assertThrows(UnsupportedOperationException.class, () -> tree.children().clear());
        assertThrows(UnsupportedOperationException.class, () -> tree.bindOrder().clear());
        assertTrue(tree.toString().contains("test"));
    }

    /** A bundle over a bundle: expansion is recursive. */
    @SubModule(WebBundle.class)
    static final class TopBundle implements ModuleEx {
        @Override
        public void bind(Binder binder) {
        }
    }

    @Test
    void nestedBundlesExpandRecursively() {
        ModuleNode tree = ModuleNode.of(TopBundle.class);

        assertEquals(List.of("TopBundle", "WebBundle", "Middle"), nodeNames(tree));
        assertEquals(Set.of(TopBundle.class, WebBundle.class, Middle.class), tree.classes());
    }

    @Test
    void classesExposeWhatDiscoveryMustNotAddAgain() {
        ModuleNode tree = ModuleNode.app("test",
            ModuleNode.of(new Marker()),
            ModuleNode.of(named("anonymous", new ArrayList<>())),
            ModuleNode.of(WebBundle.class));

        assertEquals(Set.of(Marker.class, Middle.class, WebBundle.class), tree.classes(),
            "bundles contribute their submodule classes; anonymous modules are absent");
    }

    @Test
    void ofCarriesItsDeclarationAndResolvesIt() {
        ModuleNode classLeaf = ModuleNode.of(Marker.class);
        assertEquals(Marker.class, classLeaf.type());
        assertNull(classLeaf.instance());
        assertTrue(classLeaf.children().isEmpty());
        assertTrue(classLeaf.resolve() instanceof Marker);

        Marker instance = new Marker();
        ModuleNode instanceLeaf = ModuleNode.of(instance);
        assertEquals(Marker.class, instanceLeaf.type());
        assertSame(instance, instanceLeaf.instance());
        assertSame(instance, instanceLeaf.resolve(),
            "an instance declaration resolves to itself, not a copy");

        ModuleNode bundle = ModuleNode.of(WebBundle.class);
        assertTrue(bundle.resolve() instanceof WebBundle);
        assertEquals(1, bundle.children().size());
    }

    @Test
    void applicationRootDeclaresNothingToResolve() {
        ModuleNode root = ModuleNode.app("app", ModuleNode.of(Marker.class));

        assertTrue(root.isApplication());
        assertNull(root.type());
        assertNull(root.instance());
        assertThrows(IllegalStateException.class, root::resolve);
    }

    @Test
    void fragmentIsAValueAndMayBePlacedInDifferentTrees() {
        ModuleNode fragment = ModuleNode.of(WebBundle.class);

        try (Container first = Freeway.create(ModuleNode.app("one", fragment));
             Container second = Freeway.create(ModuleNode.app("two", fragment))) {
            assertNotNull(first.get(Middle.class));
            assertNotNull(second.get(Middle.class),
                "the same bundle value resolves its submodules per container");
        }
    }

    @Test
    void blankRootNameIsRefused() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> ModuleNode.app("  "));
        assertTrue(failure.getMessage().contains("root name"), failure.getMessage());
    }

    // ── validation ──────────────────────────────────────────────

    @Test
    void twoInstancesOfOneModuleClassAreRefusedNamingBothPaths() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> ModuleNode.app("test",
                ModuleNode.of(new Marker()),
                ModuleNode.of(MarkerBundle.class)));

        assertTrue(failure.getMessage().contains(Marker.class.getName()), failure.getMessage());
        assertTrue(failure.getMessage().contains("one declaration per module class"),
            "the message states the rule: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("test → Marker")
                && failure.getMessage().contains("test → MarkerBundle → Marker"),
            "both paths are named: " + failure.getMessage());
    }

    @Test
    void subModuleCycleIsRefused() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> ModuleNode.of(CycleA.class));

        assertTrue(failure.getMessage().contains("Cycle in @SubModule"),
            failure.getMessage());
    }

    @Test
    void theSameInstanceReachedTwiceIsCollapsed() {
        List<String> log = new ArrayList<>();
        ModuleEx module = named("once", log);
        ModuleNode tree = ModuleNode.app("test",
            ModuleNode.of(module), ModuleNode.of(module));

        assertEquals(2, tree.size(), "the repeated instance keeps its first placement");
        try (Container container = Freeway.create(tree)) {
            assertEquals(List.of("once"), log, "a shared instance binds once");
        }
    }

    @Test
    void aSharedBundleValueIsCollapsed() {
        ModuleNode bundle = ModuleNode.of(WebBundle.class);
        ModuleNode tree = ModuleNode.app("test", bundle, bundle);

        assertEquals(3, tree.size(), "the repeated bundle value keeps its first placement");
        try (Container container = Freeway.create(tree)) {
            assertIterableEquals(List.of("WebBundle", "Middle"), bindNames(container.moduleTree()));
        }
    }

    @Test
    void anonymousModulesAreComparedByIdentityOnly() {
        List<String> log = new ArrayList<>();
        ModuleNode tree = ModuleNode.app("test",
            ModuleNode.of(named("first", log)),
            ModuleNode.of(named("second", log)));

        try (Container container = Freeway.create(tree)) {
            assertEquals(List.of("first", "second"), log,
                "two anonymous modules are two modules, whatever class they share");
        }
    }

    @Test
    void nullModuleOrChildIsRefused() {
        assertThrows(NullPointerException.class, () -> ModuleNode.of((ModuleEx) null));
        assertThrows(NullPointerException.class, () -> ModuleNode.app("test", (ModuleNode) null));
        assertThrows(NullPointerException.class, () -> ModuleNode.app("test", (ModuleNode[]) null));
    }

    // ── loading by class ────────────────────────────────────────

    @Test
    void modulesAreLoadedByClass() {
        try (Container container = Freeway.create(Marker.class, Other.class)) {
            assertNotNull(container.get(Marker.class));
            assertNotNull(container.get(Other.class));
            assertEquals(Set.of(Marker.class, Other.class), container.moduleTree().classes());
        }
    }

    @Test
    void classAndInstanceOfOneModuleClassAreRefused() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> ModuleNode.app("test",
                ModuleNode.of(Marker.class), ModuleNode.of(new Marker())));

        assertTrue(failure.getMessage().contains(Marker.class.getName()), failure.getMessage());
        assertTrue(failure.getMessage().contains("one declaration per module class"),
            "the message states the rule: " + failure.getMessage());
    }

    @Test
    void declaringTheSameClassTwiceIsRefused() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> ModuleNode.app("test", Marker.class, Marker.class));

        assertTrue(failure.getMessage().contains("declare the class once"),
            "the message names the fix: " + failure.getMessage());
    }

    @Test
    void moduleWithoutANoArgConstructorFailsWhenLoadingNamingTheClassAndTheFix() {
        ModuleNode node = ModuleNode.of(NeedsArguments.class);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> Freeway.create(node));

        assertTrue(failure.getMessage().contains(NeedsArguments.class.getName()),
            failure.getMessage());
        assertTrue(failure.getMessage().contains("declared as an instance"),
            "the message names the fix: " + failure.getMessage());
    }

    @Test
    void classDeclarationsResolveWhenLoadingStarts() {
        CountingModule.constructions.set(0);
        ModuleNode tree = ModuleNode.app("test", CountingModule.class);

        assertEquals(0, CountingModule.constructions.get(),
            "composition declares, it does not construct");
        try (Container first = Freeway.create(tree);
             Container second = Freeway.create(tree)) {
            assertEquals(2, CountingModule.constructions.get(),
                "each container resolves its own module");
            assertNotSame(first.get(CountingModule.class), second.get(CountingModule.class),
                "a class declaration is not shared between containers");
        }
    }

    @Test
    void classAndInstanceFormsMixInOneTree() {
        ModuleNode app = ModuleNode.app("test",
            ModuleNode.of(Marker.class),
            ModuleNode.of(new Other()),
            ModuleNode.of(WebBundle.class));

        try (Container container = Freeway.create(app)) {
            assertNotNull(container.get(Marker.class));
            assertNotNull(container.get(Other.class));
            assertNotNull(container.get(Middle.class));
        }
    }

    // ── the flat entry point stays sugar ────────────────────────

    @Test
    void flatEntryListIsTheApplicationNodesChildren() {
        List<String> log = new ArrayList<>();
        try (Container container = Freeway.create(named("a", log), named("b", log))) {
            assertEquals(List.of("a", "b"), log);
            assertEquals("application", container.moduleTree().name());
            assertEquals(2, container.moduleTree().bindOrder().size());
        }
    }

    @Test
    void distinctModuleClassesBothInstall() {
        try (Container container = Freeway.create(
            binder -> binder.bind(String.class).to(c -> "a"),
            new Marker())) {
            assertEquals("a", container.get(String.class));
            assertNotNull(container.get(Marker.class));
        }
    }
}
