package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.ioc.ModuleEx;
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
 * <p><b>Internal structure, not an entry point.</b> The public vocabulary is
 * modules plus the entry points that take them ({@code Freeway.create},
 * {@code FreewayApp.create}, {@code FreewayApp.run}); this type is what those calls
 * build, own and validate, and the shape behind the startup log. It is
 * package-private for that reason — nothing outside {@code ioc.internal} and
 * the boot layer assembles a tree, and the one public type here is
 * {@code ContainerImpl}.
 *
 * <p><b>Composition is data, and this is its type.</b> A node is either the
 * application root ({@link #app}, a name that binds nothing) or a module node
 * ({@link #of}: a class to resolve at load time, or a configured instance).
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
 * // FreewayApp.run(new OrderModule(), CloudModule.class) builds this:
 * ModuleNode app = ModuleNode.app("application",
 *     ModuleNode.of(new OrderModule()),
 *     ModuleNode.of(CloudModule.class));   // a bundle: CloudModule + @SubModule
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
 * contract — sequencing belongs to {@link com.jujin.freeway.ioc.RuntimeHook}
 * anchors and contribution
 * {@code order()}, not to where a module sits in the tree.
 */
final class ModuleNode {

    /**
     * The name the entry points use when the caller does not name the
     * application. Package-visible so the assembling side
     * ({@code ContainerImpl}) can build a default-named root without
     * restating the default.
     */
    static final String DEFAULT_APP_NAME = "application";

    /** The instance declaration, or {@code null} for a class declaration/root. */
    private final ModuleEx module;
    /** The class declaration, or {@code null} for an instance declaration/root. */
    private final Class<? extends ModuleEx> moduleType;
    /** The application root's name; {@code null} for a module node. */
    private final String rootName;
    private final List<ModuleNode> children;
    /** Every module node, pre-order — literally the order the container binds them. */
    private final List<ModuleNode> bindOrder;

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
        if (rootName == null) {
            order.add(this);
        }
        for (ModuleNode child : this.children) {
            order.addAll(child.bindOrder);
        }
        this.bindOrder = List.copyOf(order);
    }

    // ── factories ───────────────────────────────────────────────

    /**
     * The application root: a named structural node whose children are the
     * modules the application is made of. It declares no bindings of its own, so
     * it only names the tree in logs and diagnostics. The entry points build it
     * with the default name unless the caller names the application
     * ({@code Freeway.create(appName, …)}) — this is the only root factory; the
     * declaration lists they assemble are walked into nodes by the entry side
     * ({@code ContainerImpl}), which keeps this type to structure and invariants.
     */
    static ModuleNode app(String name, ModuleNode... children) {
        return normalize(new ModuleNode(null, null, rootName(name), List.of(children)));
    }

    /**
     * The module node for a single module instance. A class that declares
     * {@link SubModule} is a bundle: its declared submodules follow it in the
     * tree, so placing the bundle is placing the whole group. A submodule is an
     * ordinary module and can always be placed on its own instead — taking a
     * subset is composing the modules you want.
     */
    static ModuleNode of(ModuleEx module) {
        Objects.requireNonNull(module, "module");
        return expand(module, null, new LinkedHashSet<>());
    }

    /**
     * The module node for a single module named by class — the normal way to
     * declare one. The class is instantiated through its no-arg constructor when
     * loading starts; a module that needs constructor arguments is passed as an
     * instance instead ({@link #of(ModuleEx)}), since a module's constructor
     * carries configuration, not dependencies (there is nothing to inject before
     * the container exists). Submodules declared with {@link SubModule} are
     * placed with the module.
     */
    static ModuleNode of(Class<? extends ModuleEx> type) {
        Objects.requireNonNull(type, "module type");
        return expand(null, type, new LinkedHashSet<>());
    }

    // ── shape and declaration ───────────────────────────────────

    /**
     * The name this node is shown under: the application name for the root,
     * the class's simple name or {@link ModuleEx#name()} for a module node.
     */
    String name() {
        if (rootName != null) {
            return rootName;
        }
        return module != null ? module.name() : moduleType.getSimpleName();
    }

    /**
     * The module type of a module node — the declared class, or the instance's
     * class. {@code null} for the application root.
     */
    Class<? extends ModuleEx> type() {
        if (module != null) {
            return module.getClass();
        }
        return moduleType;
    }

    /**
     * The module to bind: a fresh no-arg instance for a class declaration, the
     * declared instance otherwise. Called once per node per load, so a class
     * declaration yields a new module for every container it is loaded into.
     *
     * @throws IllegalStateException on the application root, which binds nothing
     */
    ModuleEx resolve() {
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
                    + " constructor takes arguments is declared as an instance: new "
                    + moduleType.getSimpleName() + "(…)", e);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException(
                "Cannot instantiate module " + moduleType.getName() + ": " + cause, cause);
        }
    }

    /**
     * This node's children, in order; a plain module has none. The structural
     * view of nesting: {@link #render()} shows it to the log, and the internal
     * structure tests walk it to pin bundle expansion.
     */
    List<ModuleNode> children() {
        return children;
    }

    /**
     * Every module node in the tree, pre-order — literally the order the
     * container binds them in, each resolved ({@link #resolve()}) before it
     * binds. The application root is absent: it binds nothing.
     */
    List<ModuleNode> bindOrder() {
        return bindOrder;
    }

    /**
     * The tree as indented lines, for the startup log and diagnostics —
     * rendered from the same value the container bound, so the lines describe
     * exactly what was loaded.
     */
    String render() {
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
