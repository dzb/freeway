package com.jujin.freeway.ioc;

import com.jujin.freeway.commons.util.TreeNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The module tree the application is composed of — an immutable, validated
 * value that the container binds and holds.
 *
 * <p><b>Composition is data, and this is its type.</b> Grouping used to be a
 * method on the module ({@code subModules()}), so the module graph was
 * something the framework had to ask each module for, more than once per
 * startup, and umbrella modules owned their children — an application could
 * not replace one of them. Now the composition is built once, at the entry
 * point, with {@link #app}, {@link #leaf} and {@link #of}: a value a caller
 * places, inspects, reuses as a fragment or leaves out.
 *
 * <pre>{@code
 * ModuleNode app = ModuleNode.app("order-service",
 *     ModuleNode.leaf(new OrderModule()),
 *     CloudModules.standard());          // a fragment built the same way
 * }</pre>
 *
 * <p><b>Construction is validation.</b> {@link TreeNode} makes cycles
 * unrepresentable (a child cannot reference a node that does not exist yet),
 * and the factories additionally refuse a tree that names a module twice:
 *
 * <ul>
 *   <li>two modules of one class — the tree holds one instance per module
 *       class, so a class copied by two bundles, or declared as a sub-module
 *       and added again, is a mistake worth stopping for;</li>
 *   <li>the same instance in two places — one module instance belongs to one
 *       place in the tree;</li>
 *   <li>anonymous and lambda modules have no meaningful class, so only their
 *       identity is compared.</li>
 * </ul>
 *
 * Both failures name the paths where the module was found, and they surface
 * where the tree is built — the assembly code — rather than at container
 * startup.
 *
 * <p><b>Binding order</b> is the tree's pre-order: parents before children,
 * siblings in declaration order. It is deterministic but not a contract —
 * sequencing belongs to {@link RuntimeHook} anchors and contribution
 * {@code order()}, not to where a module sits in the tree.
 */
public final class ModuleNode {

    private final TreeNode<ModuleEx> root;
    private final List<ModuleEx> bindOrder;
    private final Set<Class<?>> classes;

    private ModuleNode(TreeNode<ModuleEx> root) {
        this.root = normalize(root);
        List<ModuleEx> order = new ArrayList<>();
        Set<Class<?>> declared = new LinkedHashSet<>();
        for (TreeNode<ModuleEx> node : this.root.preOrder()) {
            ModuleEx module = node.value();
            order.add(module);
            Class<?> type = module.getClass();
            if (!isNameless(type) && !(module instanceof GroupModule)) {
                declared.add(type); // a structural node is never discovered
            }
        }
        this.bindOrder = List.copyOf(order);
        this.classes = Set.copyOf(declared);
    }

    /**
     * The application node: a named structural root whose children are the
     * modules the application is made of. It declares no bindings of its own,
     * so it only names the tree in logs and diagnostics. The entry point reuses
     * the one it is given instead of nesting a second root around it.
     */
    public static ModuleNode app(String name, ModuleNode... children) {
        return validated(TreeNode.of(new GroupModule(name, true), roots(children)));
    }

    /**
     * A named grouping node — how a library ships several modules as one
     * fragment ({@code CloudModules.standard()}). It binds nothing; the name is
     * what the startup tree shows for the bundle.
     */
    public static ModuleNode group(String name, ModuleNode... children) {
        return validated(TreeNode.of(new GroupModule(name, false), roots(children)));
    }

    /**
     * The application node with the default name, one child per module —
     * the shape {@code Freeway.create(a, b)} and {@code FreewayApp.run(a, b)}
     * build for a flat entry list.
     */
    public static ModuleNode app(ModuleEx... modules) {
        Objects.requireNonNull(modules, "modules");
        ModuleNode[] leaves = new ModuleNode[modules.length];
        for (int i = 0; i < modules.length; i++) {
            leaves[i] = leaf(modules[i]);
        }
        return app(DEFAULT_APP_NAME, leaves);
    }

    /** A single module, with no children. */
    public static ModuleNode leaf(ModuleEx module) {
        return validated(TreeNode.leaf(Objects.requireNonNull(module, "module")));
    }

    /** A module that groups others. */
    public static ModuleNode of(ModuleEx module, ModuleNode... children) {
        Objects.requireNonNull(module, "module");
        return validated(TreeNode.of(module, roots(children)));
    }

    /**
     * A fragment over an existing tree value — the shape used when a composed
     * tree is placed inside another one. Validated like any other fragment.
     */
    public static ModuleNode of(TreeNode<ModuleEx> tree) {
        return new ModuleNode(Objects.requireNonNull(tree, "tree"));
    }

    /** The tree value — structure and traversal, shared by logs, diagnostics and tests. */
    public TreeNode<ModuleEx> tree() {
        return root;
    }

    /** This tree's root's children, in order. */
    public List<TreeNode<ModuleEx>> children() {
        return root.children();
    }

    /**
     * Whether this tree's root is an application (group) node — the named root
     * {@link #app} creates. The entry point reuses one if the caller passed
     * it, instead of nesting a second root around it.
     */
    public boolean isApplication() {
        return root.value() instanceof GroupModule group && group.application();
    }

    /**
     * Every module in the tree, pre-order — literally the order the container
     * binds them in. Includes the application node when the composition
     * declared one: it is a module like any other node, it just binds nothing.
     */
    public List<ModuleEx> bindOrder() {
        return bindOrder;
    }

    /**
     * The module classes this tree declares — what SPI discovery must not add
     * a second time. Nameless (anonymous/lambda) modules are absent: they have
     * no class identity to compare.
     */
    public Set<Class<?>> classes() {
        return classes;
    }

    /** The name of this tree's root: the application name for {@link #app}. */
    public String name() {
        return root.value().name();
    }

    /** How many nodes the tree holds. */
    public int size() {
        return bindOrder.size();
    }

    @Override
    public String toString() {
        return name() + " (" + bindOrder.size() + " module(s))";
    }

    // ── construction ────────────────────────────────────────────

    private static final String DEFAULT_APP_NAME = "application";

    private static ModuleNode validated(TreeNode<ModuleEx> root) {
        return new ModuleNode(root);
    }

    private static TreeNode<ModuleEx>[] roots(ModuleNode[] children) {
        Objects.requireNonNull(children, "children");
        @SuppressWarnings("unchecked")
        TreeNode<ModuleEx>[] trees = new TreeNode[children.length];
        for (int i = 0; i < children.length; i++) {
            trees[i] = Objects.requireNonNull(children[i], "child").root;
        }
        return trees;
    }

    /**
     * Normalizes the candidate tree in one iterative pass: the same module
     * instance reached twice is collapsed (keep the first placement — that is
     * what sharing a fragment means), a second instance of one module class is
     * refused with both paths named, and the survivors are rebuilt
     * bottom-up. Iterative throughout: a tree is data, and its size must not
     * become call depth.
     */
    private static TreeNode<ModuleEx> normalize(TreeNode<ModuleEx> root) {
        Set<ModuleEx> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Class<?>, String> byClass = new HashMap<>();
        List<ModuleEx> modules = new ArrayList<>();
        List<List<Integer>> keptChildren = new ArrayList<>();

        Deque<PathNode> pending = new ArrayDeque<>();
        pending.push(new PathNode(root, -1, List.of()));
        while (!pending.isEmpty()) {
            PathNode current = pending.pop();
            ModuleEx module = current.node().value();
            List<String> path = new ArrayList<>(current.path());
            path.add(module.name());
            String here = String.join(" → ", path);

            if (!seen.add(module)) {
                continue; // the same instance reached twice: keep the first placement
            }
            Class<?> type = module.getClass();
            if (!isNameless(type) && !isStructural(type)) {
                String sameClass = byClass.putIfAbsent(type, here);
                if (sameClass != null) {
                    throw new IllegalStateException(
                        "Module " + type.getName() + " is declared twice in the module tree, at ["
                            + sameClass + "] and [" + here + "]. The tree holds one instance per"
                            + " module class; share the single instance, or declare the class once."
                    );
                }
            }

            int index = modules.size();
            modules.add(module);
            keptChildren.add(new ArrayList<>());
            if (current.parent() >= 0) {
                keptChildren.get(current.parent()).add(index);
            }
            List<TreeNode<ModuleEx>> children = current.node().children();
            for (int i = children.size() - 1; i >= 0; i--) {
                pending.push(new PathNode(children.get(i), index, List.copyOf(path)));
            }
        }

        // Rebuild: pre-order guarantees a parent is built after its children
        // are known, so walking the survivors backwards assembles every node
        // with the children that survived.
        TreeNode<ModuleEx>[] built = newTreeArray(modules.size());
        for (int i = modules.size() - 1; i >= 0; i--) {
            List<TreeNode<ModuleEx>> children = new ArrayList<>(keptChildren.get(i).size());
            for (int child : keptChildren.get(i)) {
                children.add(built[child]);
            }
            built[i] = new TreeNode<>(modules.get(i), children);
        }
        return built[0];
    }

    @SuppressWarnings("unchecked")
    private static TreeNode<ModuleEx>[] newTreeArray(int size) {
        return new TreeNode[size];
    }

    private static boolean isNameless(Class<?> type) {
        return type.isAnonymousClass() || type.isSynthetic();
    }

    /** The structural group node: several may nest, each merely naming a level. */
    private static boolean isStructural(Class<?> type) {
        return type == GroupModule.class;
    }

    /** One node, its parent's index in the survivor list, and the path that reached it. */
    private record PathNode(TreeNode<ModuleEx> node, int parent, List<String> path) {}

    /**
     * A structural node: it names a level and declares nothing. A module rather
     * than a marker so every tree node holds a module and the traversal stays
     * uniform.
     */
    private static final class GroupModule implements ModuleEx {

        private final String name;
        private final boolean application;

        GroupModule(String name, boolean application) {
            String value = Objects.requireNonNull(name, "name").trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("A group name must not be blank");
            }
            this.name = value;
            this.application = application;
        }

        boolean application() {
            return application;
        }

        @Override
        public void bind(Binder binder) {
            // Structural node: the children declare the bindings.
        }

        @Override
        public String name() {
            return name;
        }
    }
}
