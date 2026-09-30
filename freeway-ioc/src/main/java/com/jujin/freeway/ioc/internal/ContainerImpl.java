package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.commons.bean.BeanConstructor;
import com.jujin.freeway.commons.bean.BeanIntrospector;
import com.jujin.freeway.commons.coercion.CoerceRule;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.commons.metrics.NoopMetrics;
import com.jujin.freeway.commons.scoped.ScopedCache;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.event.EventBus;
import com.jujin.freeway.ioc.LoggerSource;
import com.jujin.freeway.ioc.MissingBindingException;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.Scoping;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.freeway.ioc.extension.Extension;
import com.jujin.freeway.ioc.symbol.SymbolProvider;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/** Default {@link Container} implementation: bindings, markers, extensions, scopes, and lifecycle. */
public final class ContainerImpl implements Container {

    private static final Logger LOG = LoggerFactory.getLogger(ContainerImpl.class);

    private volatile boolean closed;
    private final BindingIndex bindingIndex = new BindingIndex();
    private final MarkerIndex markerIndex = new MarkerIndex();
    private final CoercerDefault coercer;
    private final InjectionResolver injectResolver;
    private final ServiceRuntime serviceRuntime;
    private final Shutdown shutdown;
    /** The composition this container bound — see {@link #moduleTree()}. */
    private final ModuleNode moduleTree;
    /** The contribution side of composition: DSL, stores, deferral, seal. */
    private final ContributionRegistry contributions;

    /**
     * Instances post-constructed during the realize operation currently running
     * on this thread, by identity — non-null only between a realize's entry and
     * exit.
     *
     * <p>Scoped to the realize call, not to the container, and that scope is the
     * point. "Already initialized" is not derivable from the instance, and the
     * one place the question legitimately arises is realization: a provider is
     * handed the container and {@code create(Class)} is a documented way to get
     * an injected instance, so {@code to(c -> c.create(Impl.class))} returns
     * something already initialized, and the binding then materializes it. Two
     * correct steps in a row, and {@code @PostConstruct} twice between them.
     * </p>
     *
     * <p>Deliberately NOT a container-lifetime set. {@link #create(Class)} is
     * the caller-owned path — the caller owns the lifecycle, so the container
     * must not retain those instances, and a strong per-instance set would pin
     * every object the application ever built through it. Clearing on realize
     * exit holds each reference for one realization and no longer.
     * </p>
     *
     * <p>A stack, not a single slot: a provider may itself resolve services
     * (a nested realize), and the outer one still needs its own record when it
     * resumes. Identity, not equality — two equal beans are two beans, and
     * calling {@code equals} on an uninitialized one is a hazard.
     * </p>
     */
    private final ThreadLocal<Set<Object>> realizing = new ThreadLocal<>();

    /**
     * Builds a container over the given modules by declaring them to the module tree — the entry
     * {@link com.jujin.freeway.ioc.Freeway} delegates to, so the tree type itself stays internal.
     * This is the assembling side of the entry ladder: a declaration list becomes module nodes
     * here, and {@link ModuleNode} stays structure and invariants.
     */
    public static Container of(ModuleEx... modules) {
        return new ContainerImpl(ModuleNode.app(ModuleNode.DEFAULT_APP_NAME, nodes(modules)));
    }

    /**
     * Same, with the composition root named — what the startup log and
     * composition errors show as the application line. Presentation only: the
     * name carries no identity (the container's bindings do not depend on it).
     */
    public static Container of(String name, ModuleEx... modules) {
        return new ContainerImpl(ModuleNode.app(name, nodes(modules)));
    }

    /** Same, for modules named by class (each instantiated when the container loads). */
    @SafeVarargs
    public static Container of(Class<? extends ModuleEx>... types) {
        return new ContainerImpl(ModuleNode.app(ModuleNode.DEFAULT_APP_NAME, nodes(types)));
    }

    /**
     * A container with no modules: nothing is bound. Not {@code of()} — a bare
     * {@code of()} is ambiguous between the two varargs declaration kinds, so this
     * is the arity-0 spelling of "empty", and {@link com.jujin.freeway.ioc.Freeway#create()}
     * is its only caller.
     */
    public static Container empty() {
        return new ContainerImpl(ModuleNode.app(ModuleNode.DEFAULT_APP_NAME));
    }

    /** One node per declared module instance; a null array is the empty declaration list. */
    private static ModuleNode[] nodes(ModuleEx... modules) {
        ModuleEx[] declared = modules == null ? new ModuleEx[0] : modules;
        ModuleNode[] nodes = new ModuleNode[declared.length];
        for (int i = 0; i < declared.length; i++) {
            nodes[i] = ModuleNode.of(Objects.requireNonNull(declared[i], "module"));
        }
        return nodes;
    }

    /** One node per declared module class. */
    private static ModuleNode[] nodes(Class<? extends ModuleEx>... types) {
        Objects.requireNonNull(types, "module types");
        ModuleNode[] nodes = new ModuleNode[types.length];
        for (int i = 0; i < types.length; i++) {
            nodes[i] = ModuleNode.of(Objects.requireNonNull(types[i], "module type"));
        }
        return nodes;
    }

    ContainerImpl(ModuleNode moduleTree) {
        this.moduleTree = Objects.requireNonNull(moduleTree, "moduleTree");
        this.coercer = new CoercerDefault();
        this.contributions = new ContributionRegistry(
            this, coercer, Set.of(SymbolProvider.class, CoerceRule.class));
        this.injectResolver = new InjectionResolver(this);
        this.serviceRuntime = new ServiceRuntime(this);
        this.shutdown = new Shutdown(serviceRuntime.targets());
        // The chain's coercer lets one-step resolve(spec) parse coercer-backed
        // types (Duration, user rules) — no two-step idiom anywhere. It is the
        // container's own instance, so a contributed CoerceRule reaches the
        // source's resolve(SymbolSpec) as well. Contributed tiers flow into
        // the chain live through the extension store — no install/replay step,
        // and a module that replaces the source hands the same view to its
        // replacement (see SymbolSource.of).
        SymbolSource symbolSource = SymbolSource.of(
            coercer,
            () -> contributions.extension(SymbolProvider.class).all(),
            SymbolProvider.systemProperties()
        );
        registerBuiltin(SymbolSource.class, c -> symbolSource, "SymbolSource");
        registerBuiltin(Metrics.class, c -> NoopMetrics.INSTANCE, "Metrics");
        registerBuiltin(Coercer.class, c -> coercer, "Coercer");
        registerBuiltin(LoggerSource.class, c -> LoggerSourceDefault.INSTANCE, "LoggerSource");
        registerBuiltin(Scoping.class, c -> this::scopedWithin, "Scoping");
        // The bus realizes on first resolution — always after every module has
        // bound — so a module-supplied primary Metrics observes its counters
        // instead of the bus freezing the pre-load NoopMetrics builtin. Its
        // close is deferred past every lifecycle callback (see Shutdown); a bus
        // that was never resolved has nothing to close.
        registerBuiltin(EventBus.class, EventBus::new, "EventBus");
        // Composition, in three acts: bind every module, create the deferred
        // contributions (config layer first), then seal the contribution
        // window — from here the extension stores are immutable and the read
        // side needs no locks.
        new BinderImpl(this, contributions).load(moduleTree);
        contributions.drain();
        contributions.seal();
        LOG.info("Loaded {} module(s):\n{}", moduleTree.bindOrder().size(), moduleTree.render());
    }

    BindingIndex bindingIndex() {
        return bindingIndex;
    }

    MarkerIndex markerIndex() {
        return markerIndex;
    }

    /** The composition this container bound — package-visible to the internal structure tests. */
    ModuleNode moduleTree() {
        return moduleTree;
    }

    @Override
    public <T> Extension<T> extension(Class<T> entryType) {
        requireOpen();
        return contributions.extension(entryType);
    }

    /**
     * A framework service, realized on first resolution through the standard
     * singleton path (target cache, shutdown lifecycle) like any module
     * binding, and marked {@link Builtin} so an injection point can insist on
     * it even when a module overrides the type with {@code .primary()}.
     */
    private <T> void registerBuiltin(Class<T> type, Function<Container, T> factory, String id) {
        BindingImpl<T> binding = new BindingImpl<>(this, type);
        binding.id(id).to(factory);
        binding.addMarkers(Set.of(Builtin.class));
        register(binding);
    }

    private <T> T scopedWithin(Supplier<T> work) {
        requireOpen();
        return ScopedCache.within(work);
    }

    @Override
    public <T> T create(Class<T> type) {
        // Caller-owned instance: no binding, no scope, no lifecycle — the
        // owner parameter stays null and scope validation falls back to the
        // type heuristic (see InjectionResolver). This is `new` plus dependency
        // resolution: constructor args and @Inject fields are satisfied, and
        // nothing else. @PostConstruct belongs to the managed half of the
        // lifecycle, which this path is not part of — see the javadoc on
        // Container.create.
        requireOpen();
        try {
            T value = constructInstance(type, null);
            injectResolver.injectFields(value, null);
            return value;
        } catch (Exception ex) {
            throw new RuntimeException("Unable to instantiate " + type.getName(), ex);
        }
    }

    /**
     * Construct-and-initialize, for the realize path — the owner-scoped
     * counterpart to the caller-owned {@link #create(Class)}, which stops at
     * injection.
     *
     * <p>Used when the binding itself has no provider, so it must be
     * self-sufficient. The instance it returns is a managed one: it lands in
     * the service caches and {@link Shutdown} will pair its
     * {@code @PreDestroy} at {@code close()}.
     * </p>
     *
     * @param owner the binding being realized on the realize path
     */
    <T> T create(Class<T> type, BindingImpl<?> owner) {
        requireOpen();
        try {
            T value = constructInstance(type, owner);
            initialize(value, owner);
            return value;
        } catch (Exception ex) {
            throw new RuntimeException("Unable to instantiate " + type.getName(), ex);
        }
    }

    /**
     * Post-constructs an already-injected instance without registering it.
     *
     * <p>For contributions. {@code Contribution.add(Class)} promises to invoke
     * {@code @PostConstruct} (see its javadoc), and unlike {@link
     * #create(Class)} the contribution must ask for it: a contribution is not a
     * binding, it never enters the service caches, and therefore {@link
     * Shutdown} does not walk it. That one-sidedness is the contribution
     * contract as written — it can start something, and nothing stops it —
     * and it is exactly why the caller-owned {@code create} does not
     * post-construct on its own: there, the same gap would be a silent leak
     * with no contract behind it.
     * </p>
     *
     * <p>Runs inside the caller's realize scope when there is one, so a
     * contribution met twice in one realization still fires once.
     * </p>
     */
    void postConstruct(Object instance) {
        if (claimPostConstruct(instance)) {
            Lifecycle.invokePostConstruct(instance);
        }
    }

    /** The realization closed re-check reads this; see {@link ServiceRuntime}. */
    boolean isClosed() {
        return closed;
    }

    /**
     * Pre-composition guard shared by every resolution entry point — the
     * closed flag is volatile, so the check needs no lock.
     */
    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Container is closed");
        }
    }

    @Override
    public void close() {
        // Idempotent: repeated close() must not re-run shutdown or PreDestroy
        // on services that were already released. The closed flag is set only
        // AFTER the first drain so @PreDestroy callbacks may still look services
        // up via get()/extension() while the container is draining.
        if (closed) {
            return;
        }
        synchronized (this) {
            if (closed) {
                return;
            }
            LOG.debug("Container closing — {} module(s) loaded", moduleTree.bindOrder().size());
            // The EventBus is closed only after every lifecycle callback has
            // run (Shutdown defers it), so @PreDestroy code may still publish
            // during the drain without the bus rejecting it. The realize lock
            // is deliberately NOT held across this drain: user callbacks may
            // join worker threads that realize services, and holding it there
            // would deadlock. Realization during the drain is handled by the
            // re-snapshot loop; realization after close is rejected inside
            // realize() (it re-checks the closed flag under the lock).
            RuntimeException drained = shutdown.close();
            // closed is set BEFORE the final drain, under the realize lock, so
            // get()/extension() reject new lookups from this point on while
            // PreDestroy callbacks of the earlier passes kept the documented
            // "look up services during close" contract.
            RuntimeException failure = serviceRuntime.seal(() -> {
                closed = true;
                return shutdown.drainRemaining(drained);
            });
            bindingIndex.clear();
            markerIndex.clear();
            coercer.clearRules();
            contributions.clear();
            if (failure != null) {
                LOG.error("Container close failed", failure);
                throw failure;
            }
            LOG.info("Container closed — {} module(s) unloaded", moduleTree.bindOrder().size());
        }
    }

    /**
     * Missing-binding failure that reports a raced {@link #close()} as the
     * sealed state instead: a lookup that passed {@link #requireOpen()} may
     * still race the index clear, and a misleading "missing service" would
     * point users at composition rather than shutdown. Callers throw the
     * returned exception so the failure path stays explicit.
     */
    private RuntimeException missingOrClosed(String message) {
        if (closed) {
            return new IllegalStateException("Container is closed");
        }
        return new MissingBindingException(message);
    }

    @Override
    public <T> T get(Class<T> type) {
        requireOpen();
        BindingImpl<T> binding = bindingIndex.findUnique(type);
        if (binding == null) {
            throw missingOrClosed("No service registered for type " + type.getName());
        }
        return serviceRuntime.get(binding);
    }

    @Override
    public <T> T get(Class<T> type, String id) {
        requireOpen();
        if (id == null) {
            throw new IllegalArgumentException(
                "Service id must not be null for type " + type.getName()
            );
        }
        BindingImpl<T> binding = bindingIndex.find(type, ServiceIds.normalize(id));
        if (binding == null) {
            throw missingOrClosed(
                "No service registered for type " + type.getName() + " and id " + id
            );
        }
        return serviceRuntime.get(binding);
    }

    @Override
    public <T> T get(Class<T> type, Class<? extends Annotation>... markers) {
        requireOpen();
        if (markers == null || markers.length == 0) {
            return get(type);
        }
        BindingImpl<T> binding = markerIndex.findByMarker(type, markers);
        if (binding == null) {
            throw missingOrClosed(
                    "No service registered for type " + type.getName()
                            + " with markers " + Arrays.toString(markers)
            );
        }
        return serviceRuntime.get(binding);
    }

    @Override
    public <T> boolean isActiveBinding(
        Class<T> type,
        Class<? extends Annotation>... markers
    ) {
        requireOpen();
        BindingImpl<T> binding = bindingIndex.findUnique(type);
        if (binding == null) {
            return false;
        }
        if (markers == null || markers.length == 0) {
            return true;
        }
        return binding.markers().containsAll(Set.of(markers));
    }

    /**
     * Registers a binding flushed by its module and seals it: the handle is
     * spent — any later {@code id}/{@code marker}/{@code to}/{@code scope}/
     * {@code primary}/{@code advise} call on it fails instead of mutating a
     * live container.
     */
    <T> void register(BindingImpl<T> binding) {
        bindingIndex.register(binding);
        markerIndex.sync(binding);
        binding.seal();
    }

    /**
     * Constructor injection only — no field injection, no @PostConstruct.
     * Constructor selection and argument resolution go through the same
     * {@code @Inject} rules as every other realization path.
     */
    <T> T constructInstance(Class<T> type, BindingImpl<?> owner) throws NoSuchMethodException {
        BeanConstructor constructor = BeanIntrospector.selectConstructor(type, Inject.class);
        Object[] args = injectResolver.resolveArguments(owner, type, constructor.parameters());
        return type.cast(constructor.newInstance(args));
    }

    /**
     * Field injection, then {@code @PostConstruct} — the latter at most once
     * per instance per realization.
     *
     * <p>The one-shot rule is what keeps {@code @PostConstruct} paired with a
     * {@code @PreDestroy}. Only a realized binding gets both: the binding's
     * instance is in the service caches, so {@link Shutdown} walks it at
     * {@code close()}. An instance the caller built through {@link
     * #create(Class)} is in no cache and therefore gets no {@code @PreDestroy}
     * — which is exactly why that path does not post-construct at all, and
     * why the guarantee is scoped to a realization rather than to the
     * container.
     * </p>
     *
     * <p>Field injection stays unconditional: it is assignment, so re-running
     * it is harmless, and skipping it would be a real behaviour change for a
     * caller that re-initializes on purpose. Only {@code @PostConstruct} is
     * guarded, because only it is a one-shot side effect (opening a
     * connection, registering a callback, starting a thread).
     * </p>
     */
    void initialize(Object instance, BindingImpl<?> owner) {
        injectResolver.injectFields(instance, owner);
        if (claimPostConstruct(instance)) {
            Lifecycle.invokePostConstruct(instance);
        }
    }

    /**
     * Records the instance as post-constructed in the current realize scope
     * and reports whether this call is the one that gets to run the callback.
     *
     * <p>Two callers share this: {@link #initialize} on the realize path, and
     * {@link #postConstruct} for contributions. Both may legitimately meet the
     * same instance — a contribution built from a provider that itself
     * contributed, say — and the second one must not fire the callback again.
     * </p>
     *
     * <p>Outside a realize there is nothing to deduplicate against, so the
     * answer is always yes.
     * </p>
     *
     * <p>Thread-local by design. Two threads realizing different bindings must
     * not see each other's records, and no lock is held here: a nested realize
     * on the same thread pushes its own scope, and a different thread never
     * shares this.
     * </p>
     */
    private boolean claimPostConstruct(Object instance) {
        Set<Object> scope = realizing.get();
        return scope == null || scope.add(instance);
    }

    /**
     * Opens a realize scope, returning the previous one for
     * {@link #exitRealize} — or {@code null} at the outermost level. The
     * returned value is the caller's to restore, which is what makes nesting
     * work: a provider that resolves services re-enters here.
     */
    Set<Object> enterRealize() {
        Set<Object> previous = realizing.get();
        realizing.set(Collections.newSetFromMap(new IdentityHashMap<>()));
        return previous;
    }

    /** Closes the scope opened by {@link #enterRealize}, restoring its parent. */
    void exitRealize(Set<Object> previous) {
        if (previous == null) {
            realizing.remove();
        } else {
            realizing.set(previous);
        }
    }
}
