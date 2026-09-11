package com.jujin.freeway.ioc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * The module tree: composition declared by {@link ModuleEx#subModules()},
 * flattened into bind order. This is the one resolver — the container binds
 * what it returns, and callers that need to know the module set before the
 * container exists (the application entry point, diagnostics, tests) ask here.
 *
 * <p>Order: a module precedes its sub-modules, siblings in declaration order
 * (pre-order depth-first).
 *
 * <p>Deduplication: two distinct instances of one module class fail — the tree
 * admits one instance per module class, so a class named both as a sub-module
 * and added to the application, or copied by two bundles, is a mistake worth
 * stopping for. The same <em>instance</em> reached twice (a shared sub-module,
 * or a mutual reference) is collapsed instead: that is not a supported shape,
 * it just must not recurse forever or bind twice.
 */
public final class ModuleTree {

    private ModuleTree() {}

    /** The bind order of {@code roots} and everything they declare. */
    public static List<ModuleEx> flatten(Collection<? extends ModuleEx> roots) {
        List<ModuleEx> rootsList = new ArrayList<>();
        if (roots != null) {
            for (ModuleEx root : roots) {
                if (root == null) {
                    throw new IllegalStateException("A module in the application is null");
                }
                rootsList.add(root);
            }
        }
        Deque<ModuleEx> pending = new ArrayDeque<>();
        for (int i = rootsList.size() - 1; i >= 0; i--) {
            pending.push(rootsList.get(i));
        }
        List<ModuleEx> order = new ArrayList<>();
        Set<ModuleEx> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Class<?>> seenClasses = new HashSet<>();
        while (!pending.isEmpty()) {
            ModuleEx module = pending.pop();
            if (!seen.add(module)) {
                continue;
            }
            Class<?> moduleClass = module.getClass();
            if (!moduleClass.isAnonymousClass()
                    && !moduleClass.isSynthetic()
                    && !seenClasses.add(moduleClass)) {
                throw new IllegalStateException(
                    "Module " + moduleClass.getName() + " is declared twice with two "
                        + "different instances. The module tree holds one instance per "
                        + "module class: the class was passed to the application twice, "
                        + "declared as a sub-module and added explicitly, or copied by "
                        + "two bundles. Fix: share the single instance, or declare the "
                        + "class once."
                );
            }
            order.add(module);
            List<ModuleEx> subModules = module.subModules();
            if (subModules == null) {
                throw new IllegalStateException(moduleClass.getName()
                    + ".subModules() returned null — return an empty list instead");
            }
            for (int i = subModules.size() - 1; i >= 0; i--) {
                ModuleEx sub = subModules.get(i);
                if (sub == null) {
                    throw new IllegalStateException(moduleClass.getName()
                        + ".subModules() contains null — declare only real modules");
                }
                pending.push(sub);
            }
        }
        return List.copyOf(order);
    }
}
