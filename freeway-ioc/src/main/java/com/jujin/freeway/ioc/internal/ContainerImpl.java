package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.commons.bean.BeanConstructor;
import com.jujin.freeway.commons.bean.BeanIntrospector;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.commons.metrics.NoopMetrics;
import com.jujin.freeway.commons.scoped.ScopedCache;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.ioc.LoggerSource;
import com.jujin.freeway.ioc.MissingBindingException;
import com.jujin.freeway.ioc.ModuleNode;
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

    /**
     * Thread-scope values realized by a container — the ones whose lifecycle
     * runs when their scope exits. The {@link ScopedCache} close hook acts only
     * on these; values cached by standalone {@code ScopedCache} users are left
     * untouched. Values stay registered until their scope exits (even if the
     * container closes first), so the hook always cleans them up.
     */
    private static final Set<Object> MANAGED_SCOPE_VALUES =
        Collections.synchronizedSet(
            Collections.newSetFromMap(new IdentityHashMap<>()));

    static {
        ScopedCache.onClose(v -> {
            if (!MANAGED_SCOPE_VALUES.remove(v)) {
                return;
            }
            Lifecycle.invokePreDestroy(v);
            if (v instanceof AutoCloseable c) {
                try {
                    c.close();
                } catch (Exception e) {
                    LOG.warn("Failed to close resource: {}", v.getClass().getName(), e);
                }
            }
        });
    }

    /** Marks {@code value} as container-managed, so scope exit runs its lifecycle. */
    static void manageScopeValue(Object value) {
        MANAGED_SCOPE_VALUES.add(value);
    }

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

    public ContainerImpl(ModuleNode moduleTree) {
        this.moduleTree = Objects.requireNonNull(moduleTree, "moduleTree");
        this.coercer = new CoercerDefault();
        this.contributions = new ContributionRegistry(this, coercer);
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

    @Override
    public ModuleNode moduleTree() {
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
        // type heuristic (see InjectionResolver).
        return create(type, null);
    }

    /**
     * Constructor injection + field injection + @PostConstruct, without
     * registering or caching the instance.
     *
     * @param owner the binding being realized on the realize path; {@code null}
     *              for caller-owned instances, whose scope the container cannot
     *              know
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
            // Thread-scope values are deliberately NOT unregistered here: their
            // lifecycle is bound to the scope, not the container. The global
            // ScopedCache close hook still runs PreDestroy/close when those
            // scopes exit — unregistering on close would leak them.
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

    /** Field injection, then {@code @PostConstruct}. */
    void initialize(Object instance, BindingImpl<?> owner) {
        injectResolver.injectFields(instance, owner);
        Lifecycle.invokePostConstruct(instance);
    }
}
