package com.jujin.freeway.ioc.internal;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.annotation.PostConstruct;
import com.jujin.freeway.ioc.annotation.PreDestroy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lifecycle-method resolution across a type hierarchy: a subclass override
 * shadows the parent's method (Java semantics, run once), while two
 * differently-named lifecycle methods in one hierarchy fail fast instead of
 * silently dropping one of them.
 */
class LifecycleTest {

    private static final List<String> CALLS = new ArrayList<>();

    @Test
    void overrideRunsSubclassLifecycleOnce() {
        CALLS.clear();
        Container container = Freeway.create(
            binder -> binder.bind(OverridingService.class).to(OverridingService.class));
        container.get(OverridingService.class); // realize -> @PostConstruct
        container.close();                      // @PreDestroy

        assertEquals(List.of("child-init", "child-cleanup"), CALLS,
            "the subclass override must run exactly once; the parent's "
                + "overridden method must not run");
    }

    @Test
    void parentOnlyLifecycleMethodIsUsed() {
        CALLS.clear();
        Container container = Freeway.create(
            binder -> binder.bind(ChildOnly.class).to(ChildOnly.class));
        container.get(ChildOnly.class);
        container.close();

        assertEquals(List.of("parent-init", "parent-cleanup"), CALLS,
            "a lifecycle method declared only on the parent must run");
    }

    @Test
    void distinctLifecycleMethodsInHierarchyFailFast() {
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                Freeway.create(binder ->
                    binder.bind(DistinctMethods.class).to(DistinctMethods.class))
                    .get(DistinctMethods.class),
            "two differently-named lifecycle methods in one hierarchy must "
                + "fail at startup, not silently skip one");
        assertTrue(containsHierarchyMessage(ex),
            "the failure must name the hierarchy conflict, got: " + ex.getMessage());
    }

    private static boolean containsHierarchyMessage(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t.getMessage() != null
                    && t.getMessage().contains("in the type hierarchy of")) {
                return true;
            }
        }
        return false;
    }

    static class ParentService {
        @PostConstruct
        void init() {
            CALLS.add("parent-init");
        }

        @PreDestroy
        void cleanup() {
            CALLS.add("parent-cleanup");
        }
    }

    static class OverridingService extends ParentService {
        @Override
        @PostConstruct
        void init() {
            CALLS.add("child-init");
        }

        @Override
        @PreDestroy
        void cleanup() {
            CALLS.add("child-cleanup");
        }
    }

    static class ChildOnly extends ParentService {
    }

    static class DistinctParent {
        @PostConstruct
        void init() {
        }

        @PreDestroy
        void cleanup() {
        }
    }

    static class DistinctMethods extends DistinctParent {
        @PostConstruct
        void setup() {
        }

        @PreDestroy
        void shutdown() {
        }
    }
}
