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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The module tree is a value built at the entry point: the container binds its
 * leaves in pre-order, and construction refuses a tree that names a module
 * twice.
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

    /** A named module class — subject to the one-instance-per-class rule. */
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

    // ── shape and order ─────────────────────────────────────────

    @Test
    void appNodeNamesTheRootAndBindsNothing() {
        List<String> log = new ArrayList<>();
        ModuleNode app = ModuleNode.app("orders", ModuleNode.leaf(named("a", log)));

        assertEquals("orders", app.name());
        assertEquals(2, app.size());
        assertEquals(List.of("orders", "a"), nodeNames(app),
            "the application node is the first line of the tree");
        try (Container container = Freeway.create(app)) {
            assertEquals(List.of("a"), log, "the structural root declares no bindings");
            assertSame(app, container.moduleTree());
        }
    }

    @Test
    void bindOrderIsPreOrderOverLeaves() {
        List<String> log = new ArrayList<>();
        ModuleNode tree = ModuleNode.app("test",
            ModuleNode.group("a", ModuleNode.leaf(named("a1", log))),
            ModuleNode.leaf(named("b", log)));

        try (Container container = Freeway.create(tree)) {
            assertEquals(List.of("a1", "b"), log);
            assertEquals(4, container.moduleTree().size(), "a group binds nothing but is a node");
            assertIterableEquals(List.of("a1", "b"),
                container.moduleTree().bindOrder().stream().map(ModuleRef::name).toList(),
                "binding order is the leaves' pre-order");
        }
    }

    @Test
    void renderShowsThePreOrderStructure() {
        ModuleNode tree = ModuleNode.app("test",
            ModuleNode.group("web",
                ModuleNode.leaf(named("http", new ArrayList<>())),
                ModuleNode.leaf(named("ws", new ArrayList<>()))),
            ModuleNode.leaf(named("db", new ArrayList<>())));

        assertEquals("- test\n  - web\n    - http\n    - ws\n  - db", tree.render());
    }

    @Test
    void treeValueExposesShapeAndIsImmutable() {
        ModuleNode tree = ModuleNode.app("test",
            ModuleNode.leaf(new Marker()),
            ModuleNode.group("g", ModuleNode.leaf(named("leaf", new ArrayList<>()))));

        assertEquals("test", tree.name());
        assertEquals(4, tree.size());
        assertNull(tree.ref(), "a grouping node carries no declaration");
        assertEquals(2, tree.children().size());
        assertNotNull(tree.children().get(0).ref(), "a leaf carries its declaration");
        assertTrue(tree.children().get(0).children().isEmpty());
        assertEquals("g", tree.children().get(1).name());
        assertThrows(UnsupportedOperationException.class, () -> tree.children().clear());
        assertThrows(UnsupportedOperationException.class, () -> tree.bindOrder().clear());
        assertTrue(tree.toString().contains("test"));
    }

    @Test
    void groupIsANamedFragmentThatBindsNothing() {
        List<String> log = new ArrayList<>();
        ModuleNode bundle = ModuleNode.group("acme-web",
            ModuleNode.leaf(named("http", log)),
            ModuleNode.leaf(named("ws", log)));

        try (Container container = Freeway.create(ModuleNode.app("app", bundle))) {
            assertEquals(List.of("http", "ws"), log);
            assertEquals(List.of("app", "acme-web", "http", "ws"),
                nodeNames(container.moduleTree()),
                "a bundle is shown in the tree but is not a binding node");
            assertIterableEquals(List.of("http", "ws"),
                container.moduleTree().bindOrder().stream().map(ModuleRef::name).toList());
            assertFalse(bundle.isApplication(), "a bundle is a fragment, not the app root");
            assertTrue(ModuleNode.app("app").isApplication());
        }
    }

    @Test
    void classesExposeWhatDiscoveryMustNotAddAgain() {
        ModuleNode tree = ModuleNode.app("test",
            ModuleNode.leaf(new Marker()),
            ModuleNode.leaf(named("anonymous", new ArrayList<>())));

        assertEquals(java.util.Set.of(Marker.class), tree.classes(),
            "anonymous modules have no class identity to compare");
    }

    @Test
    void fragmentIsAValueAndMayBePlacedInDifferentTrees() {
        ModuleNode fragment = ModuleNode.group("shared", ModuleNode.leaf(new Marker()));

        try (Container first = Freeway.create(ModuleNode.app("one", fragment));
             Container second = Freeway.create(ModuleNode.app("two", fragment))) {
            assertNotNull(first.get(Marker.class));
            assertNotNull(second.get(Marker.class));
        }
    }

    @Test
    void blankApplicationNameIsRefused() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> ModuleNode.app("  "));
        assertTrue(failure.getMessage().contains("group name"), failure.getMessage());
    }

    // ── validation ──────────────────────────────────────────────

    @Test
    void twoInstancesOfOneModuleClassAreRefusedNamingBothPaths() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> ModuleNode.app("test",
                ModuleNode.leaf(new Marker()),
                ModuleNode.group("other", ModuleNode.leaf(new Marker()))));

        assertTrue(failure.getMessage().contains(Marker.class.getName()), failure.getMessage());
        assertTrue(failure.getMessage().contains("one declaration per module class"),
            "the message states the rule: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("test → Marker")
                && failure.getMessage().contains("test → other → Marker"),
            "both paths are named: " + failure.getMessage());
    }

    @Test
    void theSameInstanceReachedTwiceIsCollapsed() {
        List<String> log = new ArrayList<>();
        ModuleEx module = named("once", log);
        ModuleNode tree = ModuleNode.app("test",
            ModuleNode.leaf(module), ModuleNode.leaf(module));

        assertEquals(2, tree.size(), "the repeated instance keeps its first placement");
        try (Container container = Freeway.create(tree)) {
            assertEquals(List.of("once"), log, "a shared instance binds once");
        }
    }

    @Test
    void aGroupWhoseModuleLeavesAreSharedKeepsItsShape() {
        List<String> log = new ArrayList<>();
        ModuleEx module = named("once", log);
        ModuleNode tree = ModuleNode.app("test",
            ModuleNode.leaf(module),
            ModuleNode.group("nested", ModuleNode.leaf(module)));

        assertEquals(3, tree.size(), "the duplicate leaf is dropped, its group is not");
        try (Container container = Freeway.create(tree)) {
            assertEquals(List.of("once"), log);
        }
    }

    @Test
    void anonymousModulesAreComparedByIdentityOnly() {
        List<String> log = new ArrayList<>();
        ModuleNode tree = ModuleNode.app("test",
            ModuleNode.leaf(named("first", log)),
            ModuleNode.leaf(named("second", log)));

        try (Container container = Freeway.create(tree)) {
            assertEquals(List.of("first", "second"), log,
                "two anonymous modules are two modules, whatever class they share");
        }
    }

    @Test
    void nullModuleOrChildIsRefused() {
        assertThrows(NullPointerException.class, () -> ModuleNode.leaf((ModuleEx) null));
        assertThrows(NullPointerException.class, () -> ModuleNode.group("g", (ModuleNode) null));
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
                ModuleNode.leaf(Marker.class), ModuleNode.leaf(new Marker())));

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
        ModuleNode leaf = ModuleNode.leaf(NeedsArguments.class);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> Freeway.create(leaf));

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
        List<String> log = new ArrayList<>();
        ModuleNode app = ModuleNode.app("test",
            ModuleNode.leaf(Marker.class),
            ModuleNode.leaf(new Other()),
            ModuleNode.group("g", ModuleNode.leaf(named("deep", log))));

        try (Container container = Freeway.create(app)) {
            assertNotNull(container.get(Marker.class));
            assertNotNull(container.get(Other.class));
            assertEquals(List.of("deep"), log);
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
