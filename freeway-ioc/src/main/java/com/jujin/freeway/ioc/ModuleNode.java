package com.jujin.freeway.ioc;

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
 * <p><b>Composition is data, and this is its type.</b> A node is either a
 * module declaration (a {@link ModuleRef}: a class or a configured instance,
 * see {@link #leaf}) or a named grouping node ({@link #group}, {@link #app})
 * that declares nothing of its own. Grouping used to be a method on the module
 * ({@code subModules()}), so the module graph was something the framework had
 * to ask each module for, more than once per startup, and umbrella modules
 * owned their children — an application could not replace one of them. Now the
 * composition is built once, at the entry point, with {@link #app},
 * {@link #leaf} and {@link #group}: a value a caller places, inspects, reuses
 * as a fragment or leaves out. A class declaration stays a declaration until
 * loading starts — the container resolves it while binding, so the same
 * class-only tree can be loaded by more than one container with a fresh module
 * each time.
 *
 * <pre>{@code
 * ModuleNode app = ModuleNode.app("order-service",
 *     ModuleNode.leaf(new OrderModule()),
 *     CloudModules.standard());          // a fragment built the same way
 * }</pre>
 *
 * <p><b>Construction is validation.</b> A node is built from its children, so
 * cycles are unrepresentable (a child cannot reference a node that does not
 * exist yet), and the factories additionally refuse a tree that names a module
 * twice:
 *
 * <ul>
 *   <li>two declarations of one module class — the tree holds one declaration
 *       per module class, so a class copied by two bundles, or declared as a
 *       sub-module and added again, is a mistake worth stopping for;</li>
 *   <li>the same instance in two places — one module instance belongs to one
 *       place in the tree;</li>
 *   <li>anonymous and lambda modules have no meaningful class, so only their
 *       identity is compared.</li>
 * </ul>
 *
 * Both failures name the paths where the module was found, and they surface
 * where the tree is built — the assembly code, before any module constructor
 * runs — rather than at container startup.
 *
 * <p><b>Binding order</b> is the tree's pre-order over leaves: a grouping node
 * binds nothing, a leaf binds before the leaves below it, and siblings bind in
 * declaration order. It is deterministic but not a contract — sequencing
 * belongs to {@link RuntimeHook} anchors and contribution {@code order()}, not
 * to where a module sits in the tree.
 */
public final class ModuleNode {

    private static final String DEFAULT_APP_NAME = "application";

    /** The leaf's declaration, or {@code null} for a grouping node. */
    private final ModuleRef ref;
    /** The group name, or {@code null} for a leaf. */
    private final String groupName;
    /** Whether this node is the application root. */
    private final boolean application;
    private final List<ModuleNode> children;
    /** Every leaf declaration, pre-order — literally the order the container binds. */
    private final List<ModuleRef> bindOrder;
    /** The module classes the tree declares, for SPI discovery dedup. */
    private final Set<Class<?>> classes;
    private final int size;

    private ModuleNode(
        ModuleRef ref,
        String groupName,
        boolean application,
        List<ModuleNode> children
    ) {
        this.ref = ref;
        this.groupName = groupName;
        this.application = application;
        this.children = List.copyOf(children);

        List<ModuleRef> order = new ArrayList<>();
        Set<Class<?>> declared = new LinkedHashSet<>();
        if (ref != null) {
            order.add(ref);
            if (!isNameless(ref.type())) {
                declared.add(ref.type());
            }
        }
        int count = 1;
        for (ModuleNode child : this.children) {
            order.addAll(child.bindOrder);
            declared.addAll(child.classes);
            count += child.size;
        }
        this.bindOrder = List.copyOf(order);
        this.classes = Set.copyOf(declared);
        this.size = count;
    }

    // ── factories ───────────────────────────────────────────────

    /**
     * The application node: a named structural root whose children are the
     * modules the application is made of. It declares no bindings of its own,
     * so it only names the tree in logs and diagnostics. The entry point reuses
     * the one it is given instead of nesting a second root around it.
     */
    public static ModuleNode app(String name, ModuleNode... children) {
        return normalize(new ModuleNode(null, groupName(name), true, List.of(children)));
    }

    /** The default-named application node with no children yet. */
    public static ModuleNode app() {
        return app(DEFAULT_APP_NAME);
    }

    /** An application node with no children yet. */
    public static ModuleNode app(String name) {
        return app(name, new ModuleNode[0]);
    }

    /** The application node whose children are modules named by class. */
    @SafeVarargs
    public static ModuleNode app(String name, Class<? extends ModuleEx>... types) {
        return app(name, leaves(types));
    }

    /** The default-named application node over modules named by class. */
    @SafeVarargs
    public static ModuleNode app(Class<? extends ModuleEx>... types) {
        return app(DEFAULT_APP_NAME, leaves(types));
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

    /**
     * A named grouping node — how a library ships several modules as one
     * fragment ({@code CloudModules.standard()}). It binds nothing; the name is
     * what the startup tree shows for the bundle.
     */
    public static ModuleNode group(String name, ModuleNode... children) {
        return normalize(new ModuleNode(null, groupName(name), false, List.of(children)));
    }

    /** A named grouping node over modules named by class. */
    @SafeVarargs
    public static ModuleNode group(String name, Class<? extends ModuleEx>... types) {
        return group(name, leaves(types));
    }

    /** A single module, with no children. */
    public static ModuleNode leaf(ModuleEx module) {
        return new ModuleNode(ModuleRef.of(module), null, false, List.of());
    }

    /**
     * A single module named by class — the normal way to declare one. The class
     * is instantiated through its no-arg constructor when loading starts; a
     * module that needs constructor arguments is passed as an instance instead
     * ({@link #leaf(ModuleEx)}), since a module's constructor carries
     * configuration, not dependencies (there is nothing to inject before the
     * container exists).
     */
    public static ModuleNode leaf(Class<? extends ModuleEx> type) {
        return new ModuleNode(ModuleRef.of(type), null, false, List.of());
    }

    // ── shape ───────────────────────────────────────────────────

    /**
     * The name this node is shown under: the group (application) name for a
     * grouping node, {@link ModuleRef#name()} for a leaf.
     */
    public String name() {
        return ref != null ? ref.name() : groupName;
    }

    /** The declaration of a leaf, or {@code null} for a grouping node. */
    public ModuleRef ref() {
        return ref;
    }

    /** This node's children, in order; a leaf has none. */
    public List<ModuleNode> children() {
        return children;
    }

    /**
     * Whether this node is an application ({@link #app}) root. The entry point
     * reuses one if the caller passed it, instead of nesting a second root
     * around it.
     */
    public boolean isApplication() {
        return application;
    }

    /**
     * Every leaf declaration in the tree, pre-order — literally the order the
     * container binds them in. Grouping nodes are absent: they bind nothing,
     * and class declarations are resolved (instantiated) by the loader.
     */
    public List<ModuleRef> bindOrder() {
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

    /** How many nodes the tree holds, grouping nodes included. */
    public int size() {
        return size;
    }

    /**
     * The tree as indented lines, for the startup log and diagnostics —
     * rendered from the same value the container bound, so the lines describe
     * exactly what was loaded.
     */
    public String render() {
        record Frame(ModuleNode node, int depth) {}
        StringBuilder out = new StringBuilder();
        Deque<Frame> pending = new ArrayDeque<>();
        pending.push(new Frame(this, 0));
        while (!pending.isEmpty()) {
            Frame frame = pending.pop();
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append("  ".repeat(frame.depth()))
                .append("- ")
                .append(frame.node().name());
            List<ModuleNode> kids = frame.node().children;
            for (int i = kids.size() - 1; i >= 0; i--) {
                pending.push(new Frame(kids.get(i), frame.depth() + 1));
            }
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return name() + " (" + bindOrder.size() + " module(s))";
    }

    // ── construction ────────────────────────────────────────────

    private static String groupName(String name) {
        String value = Objects.requireNonNull(name, "name").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("A group name must not be blank");
        }
        return value;
    }

    private static ModuleNode[] leaves(Class<? extends ModuleEx>[] types) {
        Objects.requireNonNull(types, "module types");
        ModuleNode[] nodes = new ModuleNode[types.length];
        for (int i = 0; i < types.length; i++) {
            nodes[i] = leaf(Objects.requireNonNull(types[i], "module type"));
        }
        return nodes;
    }

    /**
     * Normalizes the candidate tree in one iterative pass: the same module
     * instance (or the same group or class-declaration node) reached twice is
     * collapsed — keep the first placement, that is what sharing a fragment
     * means — a second declaration of one module class is refused with both
     * paths named, and the survivors are rebuilt bottom-up so every node holds
     * only surviving children and its own pre-order caches. Iterative
     * throughout: a tree is data, and its size must not become call depth.
     */
    private static ModuleNode normalize(ModuleNode candidate) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Class<?>, String> byClass = new HashMap<>();
        List<ModuleNode> sources = new ArrayList<>();
        List<List<Integer>> keptChildren = new ArrayList<>();

        Deque<Pending> pending = new ArrayDeque<>();
        pending.push(new Pending(candidate, -1, List.of()));
        while (!pending.isEmpty()) {
            Pending current = pending.pop();
            ModuleNode node = current.node();
            // An instance declaration is identified by its instance (two leaves
            // wrapping one instance are one placement), a class declaration and
            // a group by the node itself (each factory call is a new value).
            Object identity = refIdentity(node);
            if (!seen.add(identity)) {
                continue; // reached twice: keep the first placement
            }
            List<String> path = new ArrayList<>(current.path());
            path.add(node.name());
            String here = String.join(" → ", path);
            if (node.ref != null && !isNameless(node.ref.type())) {
                String sameClass = byClass.putIfAbsent(node.ref.type(), here);
                if (sameClass != null) {
                    throw new IllegalStateException(
                        "Module " + node.ref.type().getName()
                            + " is declared twice in the module tree, at ["
                            + sameClass + "] and [" + here + "]. The tree holds one declaration per"
                            + " module class; share the single instance, or declare the class once."
                    );
                }
            }

            int index = sources.size();
            sources.add(node);
            keptChildren.add(new ArrayList<>());
            if (current.parent() >= 0) {
                keptChildren.get(current.parent()).add(index);
            }
            List<ModuleNode> children = node.children;
            for (int i = children.size() - 1; i >= 0; i--) {
                pending.push(new Pending(children.get(i), index, List.copyOf(path)));
            }
        }

        // Rebuild: pre-order guarantees a parent is known after its children,
        // so walking the survivors backwards assembles every node with the
        // children that survived.
        ModuleNode[] built = new ModuleNode[sources.size()];
        for (int i = sources.size() - 1; i >= 0; i--) {
            ModuleNode source = sources.get(i);
            List<Integer> kept = keptChildren.get(i);
            List<ModuleNode> children = new ArrayList<>(kept.size());
            for (int child : kept) {
                children.add(built[child]);
            }
            built[i] = new ModuleNode(
                source.ref, source.groupName, source.application, children);
        }
        return built[0];
    }

    private static Object refIdentity(ModuleNode node) {
        return node.ref instanceof ModuleRef.OfInstance instanceRef
            ? instanceRef.instance() : node;
    }

    private static boolean isNameless(Class<?> type) {
        return type.isAnonymousClass() || type.isSynthetic();
    }

    /** One node, its parent's index in the survivor list, and the path that reached it. */
    private record Pending(ModuleNode node, int parent, List<String> path) {}
}
