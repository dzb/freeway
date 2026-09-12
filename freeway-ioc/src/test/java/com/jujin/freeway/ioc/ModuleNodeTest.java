package com.jujin.freeway.ioc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.commons.util.TreeNode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

/**
 * The module tree is a value built at the entry point: the container binds its
 * pre-order, and construction refuses a tree that names a module twice.
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

    private static List<String> names(TreeNode<ModuleEx> tree) {
        List<String> names = new ArrayList<>();
        tree.preOrder().forEach(node -> names.add(node.value().name()));
        return names;
    }

    // ── shape and order ─────────────────────────────────────────

    @Test
    void appNodeNamesTheRootAndBindsNothing() {
        List<String> log = new ArrayList<>();
        ModuleNode app = ModuleNode.app("orders", ModuleNode.leaf(named("a", log)));

        assertEquals("orders", app.name());
        assertEquals(2, app.size());
        assertIterableEquals(List.of("orders", "a"),
            names(app.tree()), "the application node is the first line of the tree");
        try (Container container = Freeway.create(app)) {
            assertEquals(List.of("a"), log, "the structural root declares no bindings");
            assertSame(app, container.moduleTree());
        }
    }

    @Test
    void bindOrderIsPreOrderDepthFirst() {
        List<String> log = new ArrayList<>();
        ModuleNode tree = ModuleNode.app("test",
            ModuleNode.of(named("a", log), ModuleNode.leaf(named("a1", log))),
            ModuleNode.leaf(named("b", log)));

        try (Container container = Freeway.create(tree)) {
            assertEquals(List.of("a", "a1", "b"), log);
            assertEquals(4, container.moduleTree().bindOrder().size());
            assertIterableEquals(List.of("test", "a", "a1", "b"),
                container.moduleTree().bindOrder().stream().map(ModuleEx::name).toList());
        }
    }

    @Test
    void neitherTraversalOrderIsABindingContract() {
        List<String> log = new ArrayList<>();
        ModuleNode tree = ModuleNode.app("test",
            ModuleNode.of(named("a", log), ModuleNode.leaf(named("a1", log))),
            ModuleNode.leaf(named("b", log)));

        assertEquals(List.of("test", "a", "a1", "b"),
            StreamSupport.stream(tree.tree().preOrder().spliterator(), false)
                .map(node -> node.value().name()).toList());
        assertEquals(List.of("test", "a", "b", "a1"),
            StreamSupport.stream(tree.tree().levelOrder().spliterator(), false)
                .map(node -> node.value().name()).toList(),
            "level order exists for display; the container binds pre-order");
    }

    @Test
    void treeValueExposesShapeAndIsImmutable() {
        ModuleNode tree = ModuleNode.app("test",
            ModuleNode.leaf(new Marker()),
            ModuleNode.of(new Other(), ModuleNode.leaf(named("leaf", new ArrayList<>()))));
        TreeNode<ModuleEx> root = tree.tree();

        assertEquals("test", root.value().name());
        assertFalse(root.isLeaf());
        assertEquals(2, root.children().size());
        assertTrue(root.children().get(0).isLeaf());
        assertEquals(3, root.height());
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
                container.moduleTree().bindOrder().stream().map(ModuleEx::name).toList());
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
        List<String> log = new ArrayList<>();
        ModuleNode fragment = ModuleNode.of(named("shared", log), ModuleNode.leaf(new Marker()));

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
                ModuleNode.of(new Other(), ModuleNode.leaf(new Marker()))));

        assertTrue(failure.getMessage().contains(Marker.class.getName()), failure.getMessage());
        assertTrue(failure.getMessage().contains("one instance per module class"),
            "the message states the rule: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("test → Marker")
                && failure.getMessage().contains("test → Other → Marker"),
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
    void sharedInstanceNestedUnderItselfIsCollapsed() {
        List<String> log = new ArrayList<>();
        ModuleEx module = named("once", log);
        ModuleNode tree = ModuleNode.of(module, ModuleNode.leaf(module));

        assertEquals(1, tree.size());
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
        assertThrows(NullPointerException.class, () -> ModuleNode.leaf(null));
        assertThrows(NullPointerException.class, () -> ModuleNode.of(new Marker(), (ModuleNode) null));
        assertThrows(NullPointerException.class, () -> ModuleNode.app("test", (ModuleNode[]) null));
    }

    // ── the flat entry point stays sugar ────────────────────────

    @Test
    void flatEntryListIsTheApplicationNodesChildren() {
        List<String> log = new ArrayList<>();
        try (Container container = Freeway.create(named("a", log), named("b", log))) {
            assertEquals(List.of("a", "b"), log);
            assertEquals("application", container.moduleTree().name());
            assertEquals(3, container.moduleTree().bindOrder().size());
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
