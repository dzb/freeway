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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    /** Package-private access for the realization closed re-check. */
    boolean isClosed() {
        return closed;
    }
    private final Map<ServiceKey, Object> serviceCache = new ConcurrentHashMap<>();
    private final Map<ServiceKey, Object> targetCache = new ConcurrentHashMap<>();
    private final SymbolSource symbolSource;
    private final CoercerDefault coercer;
    private final LoggerSource loggerSource;
    private final ProxyFactoryImpl proxyFactory;
    private final InjectionResolver injectResolver;
    private final Shutdown shutdown;
    private final ServiceRuntime serviceRuntime;
    /** The composition this container bound — see {@link #moduleTree()}. */
    private final ModuleNode moduleTree;
    /** The contribution side of composition: DSL, stores, deferral, seal. */
    private final ContributionRegistry contributions;

    public ContainerImpl(ModuleNode moduleTree) {
        this.moduleTree = Objects.requireNonNull(moduleTree, "moduleTree");
        this.coercer = new CoercerDefault();
        this.contributions = new ContributionRegistry(this, coercer);
        // The chain's coercer lets one-step resolve(spec) parse coercer-backed
        // types (Duration, user rules) — no two-step idiom anywhere. It is the
        // container's own instance, so a contributed CoerceRule reaches the
        // source's resolve(SymbolSpec) as well. Contributed tiers flow into
        // the chain live through the extension store — no install/replay step,
        // and a module that replaces the source hands the same view to its
        // replacement (see SymbolSource.of).
        this.symbolSource = SymbolSource.of(
            this.coercer,
            () -> contributions.extension(SymbolProvider.class).all(),
            SymbolProvider.systemProperties()
        );
        this.loggerSource = LoggerSourceDefault.INSTANCE;
        this.proxyFactory = new ProxyFactoryImpl();
        this.injectResolver = new InjectionResolver(this);
        this.shutdown = new Shutdown(targetCache);
        this.serviceRuntime = new ServiceRuntime(this, proxyFactory, serviceCache, targetCache);
        registerBuiltin(SymbolSource.class, symbolSource, "SymbolSource");
        registerBuiltin(Metrics.class, NoopMetrics.INSTANCE, "Metrics");
        registerBuiltin(Coercer.class, coercer, "Coercer");
        registerBuiltin(LoggerSource.class, loggerSource, "LoggerSource");
        registerBuiltin(Scoping.class, this::scopedWithin, "Scoping");
        // Message domain: both buses are container-managed builtins so the
        // documented usage (container.get(...)) works out of the box. They
        // realize lazily on first resolution — always after every module has
        // bound — so a module-supplied primary Metrics observes their
        // counters instead of the buses freezing the pre-load NoopMetrics
        // builtin. Their close is deferred past every lifecycle callback
        // (see Shutdown); a bus that was never resolved has nothing to close.
        registerBuiltinLazy(EventBus.class, EventBus::new, "EventBus");
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

    @Override
    public ModuleNode moduleTree() {
        return moduleTree;
    }

    @Override
    public <T> Extension<T> extension(Class<T> entryType) {
        requireOpen();
        return contributions.extension(entryType);
    }

    private <T> void registerBuiltin(Class<T> type, T instance, String id) {
        BindingImpl<T> binding = new BindingImpl<>(this, type);
        binding.id(id);
        binding.prebuiltInstance(instance);
        binding.addMarkers(Set.of(Builtin.class));
        register(binding);
    }

    /**
     * A builtin whose instance realizes on first resolution through the
     * standard singleton path (target cache, shutdown lifecycle). Used for
     * builtins whose dependencies come from module bindings — realizing
     * eagerly would freeze whatever the pre-load builtins hold.
     */
    private <T> void registerBuiltinLazy(Class<T> type, Function<Container, T> factory, String id) {
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
        requireOpen();
        // Constructor injection + field injection + @PostConstruct, without
        // registering or caching the instance.
        try {
            T value = constructInstance(type);
            initialize(value);
            return value;
        } catch (Error ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new RuntimeException("Unable to instantiate " + type.getName(), ex);
        }
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
        // AFTER shutdown so @PreDestroy callbacks may still look services up
        // via get()/extension() while the container is draining.
        if (closed) {
            return;
        }
        synchronized (this) {
            if (closed) {
                return;
            }
            LOG.debug("Container closing — {} module(s) loaded", moduleTree.bindOrder().size());
            // The container-managed message service (EventBus) is
            // closed only after every lifecycle callback has run (Shutdown
            // defers them), so @PreDestroy code may still publish event and
            // make calls during the drain without the buses rejecting them.
            // Deliberately NOT holding
            // ServiceRuntime.REALIZE_LOCK across the drain: user lifecycle
            // callbacks may join worker threads that realize services, and
            // holding the global lock there would deadlock. Realization during
            // the drain is handled by the re-snapshot loop; realization after
            // close is rejected inside realize() (it re-checks the closed flag
            // under the lock).
            RuntimeException failure = shutdown.close();
            synchronized (ServiceRuntime.REALIZE_LOCK) {
                // Seal + final drain + cache clear happen atomically with
                // respect to realize(). A realize() that passed its first
                // closed check may still be constructing while the drain runs
                // (it holds the lock); its target lands in targetCache either
                // before we acquire the lock (caught by the final drain pass)
                // or it blocks on the lock and the first closed check rejects
                // it. Either way no freshly realized singleton can be orphaned
                // past the cache clear. closed is set BEFORE the final drain so
                // get()/extension() reject new lookups from this point on, but
                // PreDestroy callbacks running in the drain's earlier passes
                // (closed still false) keep the documented "look up services
                // during close" contract.
                closed = true;
                failure = shutdown.drainRemaining(failure);
                serviceCache.clear();
                targetCache.clear();
            }
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
    @SuppressWarnings("unchecked")
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

    <T> void register(BindingImpl<T> binding) {
        bindingIndex.register(binding);
        markerIndex.sync(binding);
    }

    /**
     * Reflects markers added after the binding was already flushed (e.g. a
     * module holding a {@code Binding} handle that a later module extends)
     * into the marker index. No-op when the binding is not yet registered —
     * {@link #register} covers that path.
     */
    synchronized void syncMarkers(BindingImpl<?> binding) {
        if (bindingIndex.contains(binding)) {
            markerIndex.sync(binding);
        }
    }

    MarkerIndex markerIndex() {
        return markerIndex;
    }

    synchronized void updateId(BindingImpl<?> binding, String previousId, String newId) {
        boolean rekeyed = bindingIndex.updateId(binding, previousId, newId);
        if (!rekeyed) {
            return;
        }
        // The binding was re-keyed after it may already have been realized:
        // migrate any cached service/target from the old key to the new key.
        // Without this, the old key keeps serving the stale instance (which
        // would also receive @PreDestroy on close) while a get() under the new
        // key realizes a SECOND singleton — two instances, two @PreDestroy.
        // Migration keeps the "one singleton per binding" invariant. Done under
        // REALIZE_LOCK so it is serialized with in-flight realize() puts.
        ServiceKey previousKey = new ServiceKey(binding.type(), previousId);
        ServiceKey newKey = new ServiceKey(binding.type(), newId);
        synchronized (ServiceRuntime.REALIZE_LOCK) {
            Object service = serviceCache.remove(previousKey);
            if (service != null) {
                serviceCache.putIfAbsent(newKey, service);
            }
            Object target = targetCache.remove(previousKey);
            if (target != null) {
                targetCache.putIfAbsent(newKey, target);
            }
        }
    }

    /**
     * Constructor injection only — no field injection, no @PostConstruct.
     * Constructor selection and argument resolution go through the same
     * {@code @Inject} rules as every other realization path.
     */
    <T> T constructInstance(Class<T> type) throws Throwable {
        BeanConstructor constructor = BeanIntrospector.selectConstructor(type, Inject.class);
        Object[] args = injectResolver.resolveArguments(type, constructor.parameters());
        return type.cast(constructor.newInstance(args));
    }

    void initialize(Object instance) {
        if (instance == null) {
            return;
        }
        injectFields(instance);
        Lifecycle.invokePostConstruct(instance);
    }


    private void injectFields(Object instance) {
        injectResolver.injectFields(instance);
    }
}
