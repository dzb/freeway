package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.commons.bean.BeanParameter;
import com.jujin.freeway.commons.coercion.CoerceRule;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.scoped.ScopedCache;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.ioc.LoggerSource;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.Scoping;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.extension.Extension;
import com.jujin.freeway.ioc.symbol.SymbolProvider;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Default {@link Container} implementation: bindings, markers, extensions, scopes, and lifecycle. */
public final class ContainerImpl implements Container {

    private static final Logger LOG = LoggerFactory.getLogger(ContainerImpl.class);

    /**
     * Thread-scope values realized by containers, mapped to their owning
     * container. The {@link ScopedCache} close hook runs container lifecycle
     * only for these — values cached by standalone {@code ScopedCache} users
     * are left untouched. Values stay registered until their scope exits
     * (even if the owning container closes first), so the hook always cleans
     * them up.
     */
    private static final Map<Object, ContainerImpl> MANAGED_SCOPE_VALUES =
        Collections.synchronizedMap(new IdentityHashMap<>());

    static {
        ScopedCache.onClose(v -> {
            ContainerImpl owner = MANAGED_SCOPE_VALUES.remove(v);
            if (owner == null) {
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

    /** Marks a value as owned by {@code owner} so scope exit runs its lifecycle. */
    static void manageScopeValue(ContainerImpl owner, Object value) {
        MANAGED_SCOPE_VALUES.put(value, owner);
    }

    private volatile boolean closed;
    private final BindingIndex bindingIndex = new BindingIndex();
    private final MarkerIndex markerIndex = new MarkerIndex();
    private final Map<ServiceKey, Object> serviceCache = new ConcurrentHashMap<>();
    private final Map<ServiceKey, Object> targetCache = new ConcurrentHashMap<>();
    private final SymbolSourceDefault symbolSource;
    private final CoercerDefault coercer;
    private final LoggerSource loggerSource;
    private final Scoping scoping;
    private final ProxyFactory proxyFactory;
    private final InjectResolver injectResolver;
    private final InstanceFactory instanceFactory;
    private final Shutdown shutdown;
    private final ServiceRuntime serviceRuntime;
    private final Set<ModuleEx> installedModules = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Class<?>> installedClasses = ConcurrentHashMap.newKeySet();
    private final List<ModuleEx> loadedModules = new ArrayList<>();
    private final Map<Class<?>, Extension<?>> extensions = new ConcurrentHashMap<>();

    public ContainerImpl(Collection<? extends ModuleEx> modules) {
        this.symbolSource = SymbolSourceDefault.standard();
        this.coercer = new CoercerDefault();
        this.loggerSource = new LoggerSource() {
            @Override
            public Logger get(Class<?> ownerType) {
                return LoggerFactory.getLogger(ownerType);
            }

            @Override
            public Logger get(String name) {
                return LoggerFactory.getLogger(name);
            }
        };
        this.proxyFactory = new ProxyFactoryDefault();
        this.injectResolver = new InjectResolver(this);
        this.instanceFactory = new InstanceFactory(this);
        this.scoping = this::scopedWithin;
        this.shutdown = new Shutdown(serviceCache, targetCache, bindingIndex, coercer);
        this.serviceRuntime = new ServiceRuntime(this, proxyFactory, serviceCache, targetCache);
        registerBuiltin(SymbolSource.class, symbolSource, "SymbolSource");
        registerBuiltin(Coercer.class, coercer, "Coercer");
        registerBuiltin(LoggerSource.class, loggerSource, "LoggerSource");
        registerBuiltin(Scoping.class, scoping, "Scoping");
        registerBuiltin(EventBus.class, new EventBus(this), "EventBus");
        loadAll(modules);
        LOG.info("Loaded {} module(s): {}", loadedModules.size(),
            loadedModules.stream().map(m -> m.getClass().getSimpleName()).toList());
    }

    BindingIndex bindingIndex() {
        return bindingIndex;
    }

    LoggerSource loggerSource() {
        return loggerSource;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Extension<T> extension(Class<T> entryType) {
        if (closed) {
            throw new IllegalStateException("Container is closed");
        }
        return (Extension<T>) extensions.computeIfAbsent(entryType, k -> new Extension<>(k));
    }

    private <T> void registerBuiltin(Class<T> type, T instance, String id) {
        BindingImpl<T> binding = new BindingImpl<>(this, type);
        binding.id(id).to(instance);
        binding.addMarkers(Set.of(Builtin.class));
        register(binding);
    }

    void installModule(ModuleEx module, Binder binder) {
        if (!installedModules.add(module)) {
            LOG.debug("Ignoring duplicate module: {}", module.getClass().getSimpleName());
            return;
        }
        Class<?> moduleClass = module.getClass();
        // Fail fast when two distinct instances of the same module class are
        // installed — typically an explicit install plus SPI auto-discovery.
        // Lambda/anonymous modules keep identity-based semantics (they have no
        // meaningful class identity).
        if (!moduleClass.isAnonymousClass()
                && !moduleClass.isSynthetic()
                && !installedClasses.add(moduleClass)) {
            throw new IllegalStateException(
                "Module " + moduleClass.getName() + " installed twice. "
                    + "Likely cause: an explicit install plus SPI auto-discovery "
                    + "both loaded it. Fix: remove one of them, disable "
                    + "autoDiscovery, or use FreewayApp (which deduplicates by class)."
            );
        }
        LOG.debug("Installing module: {}", moduleClass.getSimpleName());
        loadedModules.add(module);
        BinderImpl binderImpl = (BinderImpl) binder;
        Class<?> previousModule = binderImpl.currentModule();
        binderImpl.setCurrentModule(moduleClass);
        module.bind(binder);
        binderImpl.restoreCurrentModule(previousModule);
        binderImpl.flushPending();
    }

    private void loadAll(Collection<? extends ModuleEx> modules) {
        BinderImpl binder = new BinderImpl(this);
        for (ModuleEx module : modules == null ? List.<ModuleEx>of() : List.copyOf(modules)) {
            installModule(module, binder);
        }
        // Instantiate class contributions only now — every module's bindings
        // are registered, so a contributed class may depend on services from
        // any module regardless of declaration order.
        binder.flushPendingCreates();
    }

    /**
     * Registers a built-in extension consumer as soon as the contribution is
     * added — independent of module order. Without this, contributions made
     * through {@code contribute(...).add(Class)} in the same module as their
     * consumers were never registered (wiring ran before deferred creates
     * flushed).
     */
    @SuppressWarnings("rawtypes")
    void wireContribution(Class<?> entryType, Object value) {
        if (entryType == SymbolProvider.class) {
            symbolSource.register((SymbolProvider) value);
        } else if (entryType == CoerceRule.class) {
            coercer.register((CoerceRule) value);
        }
    }

    private <T> T scopedWithin(Supplier<T> work) {
        if (closed) {
            throw new IllegalStateException("Container is closed");
        }
        return ScopedCache.within(work);
    }

    @Override
    public <T> T create(Class<T> type) {
        if (closed) {
            throw new IllegalStateException("Container is closed");
        }
        return instanceFactory.instantiate(type);
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
            LOG.debug("Container closing — {} module(s) loaded", loadedModules.size());
            RuntimeException failure = shutdown.close();
            closed = true;
            extensions.clear();
            // Thread-scope values are deliberately NOT unregistered here: their
            // lifecycle is bound to the scope, not the container. The global
            // ScopedCache close hook still runs PreDestroy/close when those
            // scopes exit — unregistering on close would leak them.
            if (failure != null) {
                LOG.error("Container close failed", failure);
                throw failure;
            }
            LOG.info("Container closed — {} module(s) unloaded", loadedModules.size());
        }
    }

    @Override
    public <T> T get(Class<T> type) {
        if (closed) {
            throw new IllegalStateException("Container is closed");
        }
        BindingImpl<T> binding = bindingIndex.findUnique(type);
        if (binding == null) {
            throw new IllegalArgumentException(
                "No service registered for type " + type.getName()
            );
        }
        return serviceRuntime.get(binding);
    }

    @Override
    public <T> T get(Class<T> type, String id) {
        if (closed) {
            throw new IllegalStateException("Container is closed");
        }
        BindingImpl<T> binding = bindingIndex.find(type, ServiceIds.normalize(id));
        if (binding == null) {
            throw new IllegalArgumentException(
                "No service registered for type " + type.getName() + " and id " + id
            );
        }
        return serviceRuntime.get(binding);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> type, Class<? extends Annotation>... markers) {
        if (closed) {
            throw new IllegalStateException("Container is closed");
        }
        if (markers == null || markers.length == 0) {
            return get(type);
        }
        Set<Class<? extends Annotation>> markerSet = new HashSet<>(Arrays.asList(markers));
        BindingImpl<T> binding = markerIndex.findByMarker(type, markerSet);
        if (binding == null) {
            throw new IllegalArgumentException(
                    "No service registered for type " + type.getName()
                            + " with markers " + Arrays.toString(markers)
            );
        }
        return serviceRuntime.get(binding);
    }

    <T> void register(BindingImpl<T> binding) {
        bindingIndex.register(binding);
        markerIndex.register(binding);
    }

    MarkerIndex markerIndex() {
        return markerIndex;
    }

    synchronized void updateId(BindingImpl<?> binding, String previousId, String newId) {
        bindingIndex.updateId(binding, previousId, newId);
    }

    /** Constructor injection only — no field injection, no @PostConstruct. */
    <T> T constructInstance(Class<T> type) throws Throwable {
        return instanceFactory.construct(type);
    }

    void initialize(Object instance) {
        if (instance == null) {
            return;
        }
        injectFields(instance);
        Lifecycle.invokePostConstruct(instance);
    }

    Object[] resolveArguments(Class<?> ownerType, List<BeanParameter> parameters) {
        return injectResolver.resolveArguments(ownerType, parameters);
    }

    private void injectFields(Object instance) {
        injectResolver.injectFields(instance);
    }
}
