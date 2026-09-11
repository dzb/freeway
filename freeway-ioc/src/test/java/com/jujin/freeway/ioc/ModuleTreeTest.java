package com.jujin.freeway.ioc;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Modules compose as a tree declared by {@link ModuleEx#subModules()}; the
 * container flattens the whole tree before binding anything.
 */
class ModuleTreeTest {

    /** Binds its name and declares {@code subs}; anonymous, so instances are free to repeat. */
    private static ModuleEx named(String name, List<String> log, ModuleEx... subs) {
        return new ModuleEx() {
            @Override
            public void bind(Binder binder) {
                log.add(name);
            }

            @Override
            public List<ModuleEx> subModules() {
                return List.of(subs);
            }
        };
    }

    /** A named module class — subject to the one-instance-per-class rule. */
    static final class Marker implements ModuleEx {
        @Override
        public void bind(Binder binder) {
        }
    }

    @Test
    void flattenIsThePublicResolverAndDoesNotBind() {
        List<String> log = new ArrayList<>();
        ModuleEx child = named("child", log);

        List<ModuleEx> flat = ModuleTree.flatten(List.of(named("root", log, child)));

        assertEquals(2, flat.size(),
            "callers that need the module set before the container exists use this");
        assertEquals(List.of(), log, "resolving the tree binds nothing");
    }

    @Test
    void treeBindsParentBeforeSubModulesDepthFirst() {
        List<String> log = new ArrayList<>();
        ModuleEx grandChild = named("a1", log);
        ModuleEx child = named("a", log, grandChild);
        ModuleEx sibling = named("b", log);
        ModuleEx root = named("root", log, child, sibling);

        try (Container container = Freeway.create(root)) {
            assertEquals(List.of("root", "a", "a1", "b"), log);
            assertEquals(4, container.modules().size(),
                "the tree is queryable, not just a side effect of binding");
        }
    }

    @Test
    void modulesAreExposedInBindOrder() {
        List<String> log = new ArrayList<>();
        ModuleEx child = named("child", log);
        ModuleEx root = named("root", log, child);
        try (Container container = Freeway.create(root)) {
            assertEquals(2, container.modules().size());
            assertSame(root, container.modules().get(0), "declared roots come first");
            assertSame(child, container.modules().get(1), "then their sub-modules");
            assertThrows(UnsupportedOperationException.class,
                () -> container.modules().clear(),
                "the view is a snapshot");
        }
    }

    @Test
    void sharedInstanceIsCollapsedAndBoundOnce() {
        // Not a supported shape — just defensive: the same instance declared by
        // two parents must not bind twice.
        List<String> log = new ArrayList<>();
        ModuleEx shared = named("shared", log);
        ModuleEx root = named("root", log,
            named("left", log, shared),
            named("right", log, shared));

        try (Container container = Freeway.create(root)) {
            assertEquals(List.of("root", "left", "shared", "right"), log);
            assertEquals(4, container.modules().size());
        }
    }

    @Test
    void mutualReferenceIsCollapsedInsteadOfRecursing() {
        // Also defensive: a pair of modules referencing each other must
        // terminate, with each bound once.
        List<String> log = new ArrayList<>();
        ModuleEx[] pair = new ModuleEx[2];
        pair[0] = new ModuleEx() {
            @Override
            public void bind(Binder binder) {
                log.add("first");
            }

            @Override
            public List<ModuleEx> subModules() {
                return List.of(pair[1]);
            }
        };
        pair[1] = new ModuleEx() {
            @Override
            public void bind(Binder binder) {
                log.add("second");
            }

            @Override
            public List<ModuleEx> subModules() {
                return List.of(pair[0]);
            }
        };

        try (Container container = Freeway.create(pair[0])) {
            assertEquals(List.of("first", "second"), log);
            assertEquals(2, container.modules().size());
        }
    }

    @Test
    void twoInstancesOfOneModuleClassFailFast() {
        ModuleEx root = named("root", new ArrayList<>(), new Marker(), new Marker());

        IllegalStateException ex = assertThrows(
            IllegalStateException.class, () -> Freeway.create(root));
        assertTrue(ex.getMessage().contains("declared twice"),
            "got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(Marker.class.getName()),
            "the error names the module class, got: " + ex.getMessage());
    }

    @Test
    void nullSubModulesFailsNamingTheModule() {
        ModuleEx root = new ModuleEx() {
            @Override
            public void bind(Binder binder) {
            }

            @Override
            public List<ModuleEx> subModules() {
                return null;
            }
        };

        IllegalStateException ex = assertThrows(
            IllegalStateException.class, () -> Freeway.create(root));
        assertTrue(ex.getMessage().contains("returned null"),
            "got: " + ex.getMessage());
    }
}
