package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.commons.bean.BeanParameter;
import com.jujin.freeway.commons.util.Types;
import com.jujin.freeway.commons.coercion.CoerceRule;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.scoped.ScopedCache;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.extension.Extension;
import com.jujin.freeway.ioc.LoggerSource;
import com.jujin.freeway.ioc.Module2;
import com.jujin.freeway.ioc.Scoping;
import com.jujin.freeway.ioc.symbol.SymbolProvider;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ContainerImpl implements Container {

    private static final Logger LOG = LoggerFactory.getLogger(ContainerImpl.class);

    static {
        ScopedCache.onClose(v -> {
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

    private volatile boolean closed;
    private final BindingIndex bindingIndex = new BindingIndex();
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
    private final Set<Class<?>> moduleTypes = new HashSet<>();
    private final List<Module2> loadedModules = new ArrayList<>();
    private final Map<Class<?>, Extension<?>> extensions = new ConcurrentHashMap<>();

    public ContainerImpl(Collection<? extends Module2> modules) {
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
        this.serviceRuntime = new ServiceRuntime(proxyFactory, serviceCache, targetCache);
        registerBuiltin(Container.class, this, "Container");
        registerBuiltin(SymbolSource.class, symbolSource, "SymbolSource");
        registerBuiltin(Coercer.class, coercer, "Coercer");
        registerBuiltin(LoggerSource.class, loggerSource, "LoggerSource");
        registerBuiltin(Scoping.class, scoping, "Scoping");
        loadAll(modules);
        LOG.info("Loaded {} module(s): {}", loadedModules.size(),
            loadedModules.stream().map(m -> m.getClass().getSimpleName()).toList());
        wireBuiltinExtensions();
    }

    BindingIndex bindingIndex() {
        return bindingIndex;
    }

    SymbolSource symbolSource() {
        return symbolSource;
    }

    Coercer coercer() {
        return coercer;
    }

    LoggerSource loggerSource() {
        return loggerSource;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Extension<T> extension(Class<T> entryType) {
        return (Extension<T>) extensions.computeIfAbsent(entryType, k -> new Extension<>(k));
    }

    private <T> void registerBuiltin(Class<T> type, T instance, String id) {
        BindingImpl<T> binding = new BindingImpl<>(this, type);
        binding.id(id).to(instance);
        register(binding);
    }

    void installModule(Module2 module, Binder binder) {
        Class<?> moduleType = module.getClass();
        if (moduleTypes.add(moduleType)) {
            LOG.debug("Installing module: {}", moduleType.getSimpleName());
            loadedModules.add(module);
            module.bind(binder);
            ((BinderImpl) binder).flushPending();
            return;
        }
        LOG.debug("Ignoring duplicate module: {}", moduleType.getSimpleName());
    }

    private void loadAll(Collection<? extends Module2> modules) {
        BinderImpl binder = new BinderImpl(this);
        for (Module2 module : modules == null ? List.<Module2>of() : List.copyOf(modules)) {
            installModule(module, binder);
        }
    }

    /**
     * Wire built-in consumers that depend on contributed extension values.
     * Called once after all modules have bound their contributions.
     */
    @SuppressWarnings("rawtypes")
    private void wireBuiltinExtensions() {
        // SymbolProvider -> SymbolSource
        Extension<?> spExt = extensions.get(SymbolProvider.class);
        if (spExt != null) {
            for (Object p : spExt.all()) {
                symbolSource.register((SymbolProvider) p);
            }
        }
        // CoerceRule -> Coercer
        Extension<?> crExt = extensions.get(CoerceRule.class);
        if (crExt != null) {
            for (Object rule : crExt.all()) {
                coercer.register((CoerceRule) rule);
            }
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
        return instanceFactory.instantiate(type);
    }

    @Override
    public void close() {
        LOG.debug("Container closing");
        closed = true;
        RuntimeException failure = shutdown.close();
        extensions.clear();
        if (failure != null) {
            LOG.error("Container close failed", failure);
            throw failure;
        }
        LOG.debug("Container closed");
    }

    @Override
    public <T> T get(Class<T> type) {
        if (closed) {
            throw new IllegalStateException("Container is closed");
        }
        BindingImpl<T> binding = bindingIndex.findUnique(type);
        if (binding == null) {
            return resolveUnbound(type);
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

    <T> void register(BindingImpl<T> binding) {
        bindingIndex.register(binding);
    }

    synchronized void updateId(BindingImpl<?> binding, String previousId, String newId) {
        bindingIndex.updateId(binding, previousId, newId);
    }

    private <T> T resolveUnbound(Class<T> type) {
        if (type == String.class) {
            throw noServiceRegistered(type);
        }
        if (Types.isConcrete(type)) {
            return instantiate(type);
        }
        throw noServiceRegistered(type);
    }

    private static IllegalArgumentException noServiceRegistered(Class<?> type) {
        return new IllegalArgumentException("No service registered for type " + type.getName());
    }

    <T> T construct(Class<T> type) throws Throwable {
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
