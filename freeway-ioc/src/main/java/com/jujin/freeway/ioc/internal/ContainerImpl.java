package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.commons.bean.BeanParameter;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.coercion.CoerceRule;
import com.jujin.freeway.commons.logging.LoggingBootstrap;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.CoercionRules;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.LoggerSource;
import com.jujin.freeway.ioc.Scoping;
import com.jujin.freeway.ioc.SymbolProviders;
import com.jujin.freeway.ioc.extension.ExtensionPoint;
import com.jujin.freeway.ioc.symbol.SymbolProvider;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;

public final class ContainerImpl implements Container {
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
    private final ScopeControl scopeControl;
    private final Shutdown shutdown;
    private final ServiceRuntime serviceRuntime;
    private final Map<Class<?>, ExtensionProxy> extensionProxies = new ConcurrentHashMap<>();

    public ContainerImpl(Collection<? extends com.jujin.freeway.ioc.Module> modules) {
        this.symbolSource = SymbolSourceDefault.standard();
        this.coercer = new CoercerDefault();
        this.loggerSource = new LoggerSource() {
            @Override
            public Logger get(Class<?> ownerType) {
                return LoggingBootstrap.logger(ownerType);
            }

            @Override
            public Logger get(String name) {
                return LoggingBootstrap.logger(name);
            }
        };
        this.proxyFactory = new ProxyFactoryDefault();
        this.injectResolver = new InjectResolver(this);
        this.instanceFactory = new InstanceFactory(this);
        this.scopeControl = new ScopeControl(() -> closed);
        this.scoping = scopeControl::within;
        this.shutdown = new Shutdown(
            scopeControl,
            serviceCache,
            targetCache,
            bindingIndex,
            coercer
        );
        this.serviceRuntime = new ServiceRuntime(scopeControl, proxyFactory, serviceCache, targetCache);
        registerBuiltin(Container.class, this, "Container");
        registerBuiltin(SymbolSource.class, symbolSource, "SymbolSource");
        registerBuiltin(Coercer.class, coercer, "Coercer");
        registerBuiltin(LoggerSource.class, loggerSource, "LoggerSource");
        registerBuiltin(Scoping.class, scoping, "Scoping");
        loadModules(modules);
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

    @SuppressWarnings("unchecked")
    ExtensionProxy extension(Class<?> pointType) {
        return extensionProxies.computeIfAbsent(pointType, pt -> {
            ExtensionProxy ext = ExtensionProxy.forPoint(pt);
            Object proxy = ext.proxy(pt);
            BindingImpl binding = new BindingImpl(this, pt);
            binding.id(pt.getSimpleName()).to(proxy);
            register(binding);
            return ext;
        });
    }

    private <T> void registerBuiltin(Class<T> type, T instance, String id) {
        BindingImpl<T> binding = new BindingImpl<>(this, type);
        binding.id(id).to(instance);
        register(binding);
    }

    private void loadModules(Collection<? extends com.jujin.freeway.ioc.Module> modules) {
        Binder binder = new BinderImpl(this);
        for (com.jujin.freeway.ioc.Module module : modules == null
            ? List.<com.jujin.freeway.ioc.Module>of()
            : List.copyOf(modules)) {
            module.bind(binder);
        }
    }

    /**
     * Wire built-in consumers that depend on contributed extension points.
     * Called once after all modules have bound their contributions.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void wireBuiltinExtensions() {
        // SymbolProviders → SymbolSource
        ExtensionProxy spExt = extensionProxies.get(SymbolProviders.class);
        if (spExt != null) {
            for (Object p : spExt.resolveAll()) {
                symbolSource.register((SymbolProvider) p);
            }
        }
        // CoercionRules → Coercer
        ExtensionProxy crExt = extensionProxies.get(CoercionRules.class);
        if (crExt != null) {
            for (Object rule : crExt.resolveAll()) {
                coercer.register((CoerceRule) rule);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        RuntimeException failure = shutdown.close();
        if (failure != null) {
            throw failure;
        }
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
        if (isConcrete(type)) {
            return instantiate(type);
        }
        // Auto-create empty extension point if it hasn't been registered yet
        if (ExtensionPoint.class.isAssignableFrom(type) && type.isInterface()) {
            extension(type); // ensures ExtensionProxy + binding exist
            // Re-resolve from binding index now that the binding exists
            BindingImpl<T> extBinding = bindingIndex.findUnique(type);
            if (extBinding != null) {
                return serviceRuntime.get(extBinding);
            }
        }
        throw noServiceRegistered(type);
    }

    private static boolean isConcrete(Class<?> type) {
        return !type.isInterface() && !Modifier.isAbstract(type.getModifiers());
    }

    private static IllegalArgumentException noServiceRegistered(Class<?> type) {
        return new IllegalArgumentException("No service registered for type " + type.getName());
    }

    <T> T instantiate(Class<T> type) {
        return instanceFactory.instantiate(type);
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
