package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.annotation.SubModule;
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
import java.util.stream.Collectors;

/**
 * The module tree the application is composed of — an immutable, validated
 * value that the container binds and holds.
 *
 * <p><b>Composition is data, and this is its type.</b> A node is either the
 * application root ({@link #app}, a name that binds nothing) or a module node
 * ({@link #leaf}: a class to resolve at load time, or a configured instance).
 * A module whose class declares {@link SubModule} is a bundle: its declared
 * submodules follow it in the tree, so the unit a library ships and the unit
 * the startup tree shows are the same thing — the module class itself.
 * Grouping used to be a method on the module ({@code subModules()}), so the
 * module graph was something the framework had to ask each module for, more
 * than once per startup, and umbrella modules owned their children. Now the
 * composition is built once, at the entry point, from static declarations and
 * module values a caller places, inspects or takes apart. A class declaration
 * stays a declaration until loading starts — the container resolves it while
 * binding, so the same class-only tree can be loaded by more than one container
 * with a fresh module each time.
 *
 * <pre>{@code
 * ModuleNode app = ModuleNode.app("order-service",
 *     ModuleNode.leaf(new OrderModule()),
 *     ModuleNode.leaf(CloudModule.class));   // a bundle: CloudModule + @SubModule
 * }</pre>
 *
 * <p><b>Construction is validation.</b> A node is built from its children, so
 * cycles through {@link SubModule} and through the value graph are refused, and
 * the factories additionally refuse a tree that names a module twice:
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
 * <p><b>Binding order</b> is the tree's pre-order over module nodes: the
 * application root binds nothing, a module binds before the modules below it,
 * and siblings bind in declaration order. It is deterministic but not a
 * contract — sequencing belongs to {@link RuntimeHook} anchors and contribution
 * {@code order()}, not to where a module sits in the tree.
 */
public final class ModuleNode {

    private static final String DEFAULT_APP_NAME = "application";

    /** The instance declaration, or {@code null} for a class declaration/root. */
    private final ModuleEx module;
    /** The class declaration, or {@code null} for an instance declaration/root. */
    private final Class<? extends ModuleEx> moduleType;
    /** The application root's name; {@code null} for a module node. */
    private final String rootName;
    private final List<ModuleNode> children;
    /** Every module node, pre-order — literally the order the container binds them. */
    private final List<ModuleNode> bindOrder;
    /** The module classes the tree declares, for SPI discovery dedup. */
    private final Set<Class<?>> classes;
    private final int size;

    private ModuleNode(
        ModuleEx module,
        Class<? extends ModuleEx> moduleType,
        String rootName,
        List<ModuleNode> children
    ) {
        this.module = module;
        this.moduleType = moduleType;
        this.rootName = rootName;
        this.children = List.copyOf(children);

        List<ModuleNode> order = new ArrayList<>();
        Set<Class<?>> declared = new LinkedHashSet<>();
        if (rootName == null) {
            order.add(this);
            if (module != null) {
                if (!isNameless(module.getClass())) {
                    declared.add(module.getClass());
                }
            } else {
                declared.add(moduleType);
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
     * The application root: a named structural node whose children are the
     * modules the application is made of. It declares no bindings of its own,
     * so it only names the tree in logs and diagnostics. The entry point reuses
     * the one it is given instead of nesting a second root around it.
     */
    public static ModuleNode app(String name, ModuleNode... children) {
        return normalize(new ModuleNode(null, null, rootName(name), List.of(children)));
    }

    /** The default-named application root with no children yet. */
    public static ModuleNode app() {
        return app(DEFAULT_APP_NAME, new ModuleNode[0]);
    }

    /**
     * An application root with no children yet. Also the one-arg form's
     * disambiguator: without it, {@code app("name")} would match the
     * {@link ModuleNode} and {@link Class} varargs overloads equally.
     */
    public static ModuleNode app(String name) {
        return app(name, new ModuleNode[0]);
    }

    /** The application root whose children are modules named by class. */
    @SafeVarargs
    public static ModuleNode app(String name, Class<? extends ModuleEx>... types) {
        return app(name, modules(types));
    }

    /** The default-named application root over modules named by class. */
    @SafeVarargs
    public static ModuleNode app(Class<? extends ModuleEx>... types) {
        return app(DEFAULT_APP_NAME, modules(types));
    }

    /**
     * The application root with the default name, one child per module —
     * the shape {@code Freeway.create(a, b)} and {@code FreewayApp.run(a, b)}
     * build for a flat entry list.
     */
    public static ModuleNode app(ModuleEx... modules) {
        Objects.requireNonNull(modules, "modules");
        ModuleNode[] nodes = new ModuleNode[modules.length];
        for (int i = 0; i < modules.length; i++) {
            nodes[i] = leaf(modules[i]);
        }
        return app(DEFAULT_APP_NAME, nodes);
    }

    /**
     * A single module instance. A class that declares {@link SubModule} is a
     * bundle: its declared submodules follow it in the tree, so placing the
     * bundle is placing the whole group. A submodule is an ordinary module and
     * can always be placed on its own instead — taking a subset is composing
     * the modules you want.
     */
    public static ModuleNode leaf(ModuleEx module) {
        Objects.requireNonNull(module, "module");
        return expand(module, null, new LinkedHashSet<>());
    }

    /**
     * A single module named by class — the normal way to declare one. The class
     * is instantiated through its no-arg constructor when loading starts; a
     * module that needs constructor arguments is passed as an instance instead
     * ({@link #leaf(ModuleEx)}), since a module's constructor carries
     * configuration, not dependencies (there is nothing to inject before the
     * container exists). Submodules declared with {@link SubModule} are placed
     * with the module.
     */
    public static ModuleNode leaf(Class<? extends ModuleEx> type) {
        Objects.requireNonNull(type, "module type");
        return expand(null, type, new LinkedHashSet<>());
    }

    // ── shape and declaration ───────────────────────────────────

    /**
     * The name this node is shown under: the application name for the root,
     * the class's simple name or {@link ModuleEx#name()} for a module node.
     */
    public String name() {
        if (rootName != null) {
            return rootName;
        }
        return module != null ? module.name() : moduleType.getSimpleName();
    }

    /**
     * The module type of a module node — the declared class, or the instance's
     * class. {@code null} for the application root.
     */
    public Class<? extends ModuleEx> type() {
        if (module != null) {
            return module.getClass();
        }
        return moduleType;
    }

    /** The configured instance of an instance declaration; {@code null} otherwise. */
    public ModuleEx instance() {
        return module;
    }

    /**
     * The module to bind: a fresh no-arg instance for a class declaration, the
     * declared instance otherwise. Called once per node per load, so a class
     * declaration yields a new module for every container it is loaded into.
     *
     * @throws IllegalStateException on the application root, which binds nothing
     */
    public ModuleEx resolve() {
        if (module != null) {
            return module;
        }
        if (moduleType == null) {
            throw new IllegalStateException(
                "The application root '" + rootName + "' declares no module to resolve");
        }
        try {
            var constructor = moduleType.getDeclaredConstructor();
            constructor.trySetAccessible();
            return constructor.newInstance();
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(
                "Module " + moduleType.getName() + " has no no-arg constructor. A module whose"
                    + " constructor takes arguments is declared as an instance: ModuleNode"
                    + ".leaf(new " + moduleType.getSimpleName() + "(…))", e);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException(
                "Cannot instantiate module " + moduleType.getName() + ": " + cause, cause);
        }
    }

    /** This node's children, in order; a leaf has none. */
    public List<ModuleNode> children() {
        return children;
    }

    /**
     * Whether this node is the application ({@link #app}) root. The entry point
     * reuses one if the caller passed it, instead of nesting a second root
     * around it.
     */
    public boolean isApplication() {
        return rootName != null;
    }

    /**
     * Every module node in the tree, pre-order — literally the order the
     * container binds them in, each resolved ({@link #resolve()}) before it
     * binds. The application root is absent: it binds nothing.
     */
    public List<ModuleNode> bindOrder() {
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

    /** How many nodes the tree holds, the application root included. */
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

    private static String rootName(String name) {
        String value = Objects.requireNonNull(name, "name").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("A root name must not be blank");
        }
        return value;
    }

    private static ModuleNode[] modules(Class<? extends ModuleEx>[] types) {
        Objects.requireNonNull(types, "module types");
        ModuleNode[] nodes = new ModuleNode[types.length];
        for (int i = 0; i < types.length; i++) {
            nodes[i] = leaf(Objects.requireNonNull(types[i], "module type"));
        }
        return nodes;
    }

    /**
     * Builds a module node and, when its class declares {@link SubModule}, the
     * declared submodule nodes below it. Expansion is static: it reads the
     * annotation, never calls a module method, so the tree stays the composition
     * the entry point built. A cycle (a class reaching itself through
     * {@code @SubModule}) is a construction mistake and fails here.
     */
    private static ModuleNode expand(
        ModuleEx module, Class<? extends ModuleEx> type, Set<Class<?>> visiting
    ) {
        Class<? extends ModuleEx> declared = module != null ? module.getClass() : type;
        List<ModuleNode> children = declaredSubModules(declared, visiting);
        ModuleNode candidate = new ModuleNode(
            module, module == null ? type : null, null, children);
        return children.isEmpty() ? candidate : normalize(candidate);
    }

    private static List<ModuleNode> declaredSubModules(
        Class<? extends ModuleEx> type, Set<Class<?>> visiting
    ) {
        SubModule sub = type.getAnnotation(SubModule.class);
        if (sub == null || sub.value().length == 0) {
            return List.of();
        }
        if (!visiting.add(type)) {
            throw new IllegalStateException(
                "Cycle in @SubModule: " + visiting.stream()
                    .map(Class::getSimpleName).collect(Collectors.joining(" → "))
                    + " → " + type.getSimpleName());
        }
        try {
            List<ModuleNode> children = new ArrayList<>(sub.value().length);
            for (Class<? extends ModuleEx> child : sub.value()) {
                children.add(expand(null, child, visiting));
            }
            return children;
        } finally {
            visiting.remove(type);
        }
    }

    /**
     * Normalizes the candidate tree in one iterative pass: the same module
     * instance (or the same class-declaration node) reached twice is collapsed
     * — keep the first placement, that is what sharing a fragment means — a
     * second declaration of one module class is refused with both paths named,
     * and the survivors are rebuilt bottom-up so every node holds only surviving
     * children and its own pre-order caches. Iterative throughout: a tree is
     * data, and its size must not become call depth.
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
            // An instance declaration is identified by its instance (two nodes
            // wrapping one instance are one placement), a class declaration and
            // the root by the node itself (each factory call is a new value).
            Object identity = node.module != null ? node.module : node;
            if (!seen.add(identity)) {
                continue; // reached twice: keep the first placement
            }
            List<String> path = new ArrayList<>(current.path());
            path.add(node.name());
            String here = String.join(" → ", path);
            if (node.type() != null && !isNameless(node.type())) {
                String sameClass = byClass.putIfAbsent(node.type(), here);
                if (sameClass != null) {
                    throw new IllegalStateException(
                        "Module " + node.type().getName()
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
                source.module, source.moduleType, source.rootName, children);
        }
        return built[0];
    }

    private static boolean isNameless(Class<?> type) {
        return type.isAnonymousClass() || type.isSynthetic();
    }

    /** One node, its parent's index in the survivor list, and the path that reached it. */
    private record Pending(ModuleNode node, int parent, List<String> path) {}
}
