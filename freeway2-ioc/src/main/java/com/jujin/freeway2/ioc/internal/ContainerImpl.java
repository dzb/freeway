package com.jujin.freeway2.ioc.internal;

import com.jujin.freeway2.ioc.AfterRealized;
import com.jujin.freeway2.ioc.Binding;
import com.jujin.freeway2.ioc.Binder;
import com.jujin.freeway2.ioc.Container;
import com.jujin.freeway2.ioc.Scope;
import com.jujin.freeway2.ioc.ServiceId;
import com.jujin.freeway2.ioc.annotation.Inject;
import com.jujin.freeway2.ioc.annotation.Named;
import com.jujin.freeway2.commons.bean.BeanIntrospector;
import com.jujin.freeway2.commons.bean.BeanConstructor;
import com.jujin.freeway2.commons.bean.BeanPlan;
import com.jujin.freeway2.commons.bean.BeanProperty;
import com.jujin.freeway2.commons.bean.BeanParameter;
import com.jujin.freeway2.commons.scalar.Coercer;
import com.jujin.freeway2.commons.scalar.CoercionRule;
import com.jujin.freeway2.commons.scalar.DefaultCoercer;
import com.jujin.freeway2.ioc.annotation.ExtensionPoint;
import com.jujin.freeway2.ioc.annotation.IntermediateType;
import com.jujin.freeway2.ioc.annotation.Symbol;
import com.jujin.freeway2.ioc.symbol.SymbolProvider;
import com.jujin.freeway2.ioc.symbol.SymbolSource;
import com.jujin.freeway2.ioc.annotation.Value;
import java.lang.reflect.Constructor;
import java.lang.reflect.AnnotatedElement;
import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 容器核心实现，管理服务生命周期、依赖注入、配置展开和扩展点。
 * <p>
 * proxy 相关的实现（lazy proxy、advised proxy）已提取到
 * {@link DefaultProxyFactory}，此处只做决策。
 */
public final class ContainerImpl implements Container {
    private volatile boolean closed;
    private final Map<ServiceKey, BindingImpl<?>> bindings = new LinkedHashMap<>();
    private final Map<ServiceKey, Object> serviceCache = new ConcurrentHashMap<>();
    private final Map<ServiceKey, Object> targetCache = new ConcurrentHashMap<>();
    private final DefaultSymbolSource symbolSource;
    private final DefaultCoercer coercer;
    private final DefaultProxyFactory proxyFactory;
    private final ExtensionRegistry extensionRegistry;

    public ContainerImpl(Collection<? extends com.jujin.freeway2.ioc.Module> modules) {
        this.symbolSource = DefaultSymbolSource.standard();
        this.coercer = new DefaultCoercer();
        this.proxyFactory = new DefaultProxyFactory();
        this.extensionRegistry = new ExtensionRegistry();
        registerBuiltin(Container.class, this, "Container");
        registerBuiltin(SymbolSource.class, symbolSource, "SymbolSource");
        registerBuiltin(Coercer.class, coercer, "Coercer");
        loadModules(modules);
        wireExtensions();
    }

    ExtensionRegistry extensionRegistry() {
        return extensionRegistry;
    }

    private <T> void registerBuiltin(Class<T> type, T instance, String id) {
        BindingImpl<T> binding = new BindingImpl<>(this, type);
        binding.id(ServiceId.of(id)).to(instance);
        register(binding);
    }

    private void loadModules(Collection<? extends com.jujin.freeway2.ioc.Module> modules) {
        Binder binder = new BinderImpl(this);
        for (com.jujin.freeway2.ioc.Module module : modules == null ? List.<com.jujin.freeway2.ioc.Module>of() : List.copyOf(modules)) {
            module.bind(binder);
        }
    }

    private void wireExtensions() {
        for (Object value : extensionRegistry.values(SymbolProvider.class)) {
            if (value instanceof SymbolProvider provider) {
                symbolSource.register(provider);
            }
        }
        for (Object value : extensionRegistry.values(CoercionRule.class)) {
            if (value instanceof CoercionRule<?, ?> rule) {
                coercer.register(rule);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        RuntimeException failure = null;
        java.util.Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        // 先拍快照再遍历，防止 close() 过程中回调 get() 修改 targetCache
        for (Object value : List.copyOf(targetCache.values())) {
            if (!(value instanceof AutoCloseable closeable) || !seen.add(value)) {
                continue;
            }
            try {
                closeable.close();
            } catch (Exception ex) {
                if (failure == null) {
                    failure = new RuntimeException("Unable to close container-managed resource", ex);
                } else {
                    failure.addSuppressed(ex);
                }
            }
        }
        serviceCache.clear();
        targetCache.clear();
        bindings.clear();
        coercer.clearCache();
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public <T> T get(Class<T> type) {
        if (closed) {
            throw new IllegalStateException("Container is closed");
        }
        BindingImpl<T> binding = findUniqueBinding(type);
        if (binding == null) {
            return resolveUnbound(type);
        }
        return getByBinding(binding);
    }

    @Override
    public <T> T get(Class<T> type, ServiceId id) {
        if (closed) {
            throw new IllegalStateException("Container is closed");
        }
        BindingImpl<T> binding = findBinding(type, id);
        if (binding == null) {
            throw new IllegalArgumentException(
                "No service registered for type " + type.getName() + " and id " + id
            );
        }
        return getByBinding(binding);
    }

    <T> void register(BindingImpl<T> binding) {
        ServiceKey key = new ServiceKey(binding.type(), binding.id());
        if (bindings.putIfAbsent(key, binding) != null) {
            throw new IllegalStateException(
                "Duplicate binding for type " + binding.type().getName() + " and id " + binding.id()
            );
        }
    }

    synchronized void updateBindingId(BindingImpl<?> binding, ServiceId previousId, ServiceId newId) {
        if (Objects.equals(previousId, newId)) {
            return;
        }
        ServiceKey previousKey = new ServiceKey(binding.type(), previousId);
        BindingImpl<?> current = bindings.get(previousKey);
        if (current != binding) {
            return;
        }
        ServiceKey newKey = new ServiceKey(binding.type(), newId);
        BindingImpl<?> existing = bindings.get(newKey);
        if (existing != null && existing != binding) {
            throw new IllegalStateException(
                "Duplicate binding for type " + binding.type().getName() + " and id " + newId
            );
        }
        bindings.remove(previousKey);
        bindings.put(newKey, binding);
    }

    @SuppressWarnings("unchecked")
    private <T> BindingImpl<T> findBinding(Class<T> type, ServiceId id) {
        BindingImpl<?> exact = bindings.get(new ServiceKey(type, id));
        if (exact != null) {
            return (BindingImpl<T>) exact;
        }
        return findSingleBinding(
            type,
            binding -> id.equals(binding.id()) && type.isAssignableFrom(binding.type()),
            "Multiple services match type " + type.getName() + " and id " + id
        );
    }

    @SuppressWarnings("unchecked")
    private <T> BindingImpl<T> findUniqueBinding(Class<T> type) {
        BindingImpl<T> unique = null;
        BindingImpl<T> primary = null;
        boolean multiple = false;
        for (BindingImpl<?> binding : bindings.values()) {
            if (!type.isAssignableFrom(binding.type())) {
                continue;
            }
            if (unique != null) {
                multiple = true;
            } else {
                unique = (BindingImpl<T>) binding;
            }
            if (binding.isPrimary()) {
                if (primary != null && primary != binding) {
                    throw new IllegalArgumentException(
                        "Multiple primary services match type " + type.getName()
                    );
                }
                primary = (BindingImpl<T>) binding;
            }
        }
        if (!multiple) {
            return unique;
        }
        if (primary != null) {
            return primary;
        }
        throw new IllegalArgumentException(
            "Multiple services match type " + type.getName() + "; mark one binding as primary()"
        );
    }

    @SuppressWarnings("unchecked")
    private <T> BindingImpl<T> findSingleBinding(
        Class<T> type,
        Predicate<BindingImpl<?>> predicate,
        String multipleMessage
    ) {
        BindingImpl<T> match = null;
        for (BindingImpl<?> binding : bindings.values()) {
            if (predicate.test(binding)) {
                if (match != null) {
                    throw new IllegalArgumentException(multipleMessage);
                }
                match = (BindingImpl<T>) binding;
            }
        }
        return match;
    }

    private <T> T resolveUnbound(Class<T> type) {
        if (type == String.class) {
            throw noServiceRegistered(type);
        }
        if (isConcrete(type)) {
            return instantiateType(type);
        }
        throw noServiceRegistered(type);
    }

    private static boolean isConcrete(Class<?> type) {
        return !type.isInterface() && !Modifier.isAbstract(type.getModifiers());
    }

    private static IllegalArgumentException noServiceRegistered(Class<?> type) {
        return new IllegalArgumentException("No service registered for type " + type.getName());
    }

    private <T> T getByBinding(BindingImpl<T> binding) {
        ServiceKey key = new ServiceKey(binding.type(), binding.id());
        if (binding.scope() == Scope.PROTOTYPE) {
            return binding.type().cast(binding.directInstance());
        }

        Object cached = serviceCache.get(key);
        if (cached != null) {
            return binding.type().cast(cached);
        }

        Object service;
        if (binding.isProxiable()) {
            service = binding.advices().isEmpty()
                ? proxyFactory.create(
                    binding.type(),
                    () -> realize(binding),
                    binding.type().getSimpleName() + "[" + binding.id().value() + "]"
                )
                : createAdvisedProxy(binding);
        } else {
            if (!binding.advices().isEmpty()) {
                throw new IllegalArgumentException(
                    "Advisor is not supported on non-interface type " + binding.type().getName() +
                    ". To use advice, bind " + binding.type().getName() + " to an interface."
                );
            }
            service = realize(binding);
        }

        serviceCache.putIfAbsent(key, service);
        if (service instanceof AfterRealized ar) {
            ar.afterRealized();
        }
        return binding.type().cast(service);
    }

    @SuppressWarnings("unchecked")
    private <T> T realize(BindingImpl<T> binding) {
        ServiceKey key = new ServiceKey(binding.type(), binding.id());
        return (T) targetCache.computeIfAbsent(key, k -> binding.directInstance());
    }

    @SuppressWarnings("unchecked")
    private <T> T createAdvisedProxy(BindingImpl<T> binding) {
        return proxyFactory.createAdvised(
            binding.type(),
            () -> realize(binding),
            binding.type().getSimpleName() + "[" + binding.id().value() + "]",
            binding.advices()
        );
    }

    <T> T instantiateType(Class<T> type) {
        try {
            T value = constructType(type);
            initialize(value);
            return value;
        } catch (Error ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new RuntimeException("Unable to instantiate " + type.getName(), ex);
        }
    }

    <T> T constructType(Class<T> type) throws Throwable {
        BeanConstructor constructor = selectConstructor(type);
        Object[] args = resolveArguments(constructor.parameters());
        return type.cast(constructor.newInstance(args));
    }

    /**
     * 选择用于实例化的构造函数，规则如下：
     * <ol>
     *   <li>如果存在标注了 {@code @Inject} 的构造函数，选择该构造函数</li>
     *   <li>如果有多个标注了 {@code @Inject} 的构造函数，抛出
     *       {@link IllegalArgumentException}</li>
     *   <li>如果没有标注 {@code @Inject} 的构造函数，选择参数最多的那个</li>
     * </ol>
     * 注意：构造函数参数上的 {@code @Inject} 注解不影响构造函数的选择——
     * 参数级 {@code @Inject} 只用于选择注入目标，不用于选择构造函数。
     *
     * @param type 目标类型
     * @return 选中的构造函数
     * @throws NoSuchMethodException 如果类型没有可用的构造函数
     */
    private BeanConstructor selectConstructor(Class<?> type) throws NoSuchMethodException {
        Constructor<?>[] constructors = type.getDeclaredConstructors();
        if (constructors.length == 0) {
            return BeanIntrospector.constructor(type.getDeclaredConstructor());
        }
        BeanConstructor injected = null;
        BeanConstructor maxParams = null;
        for (Constructor<?> constructor : constructors) {
            BeanConstructor candidate = BeanIntrospector.constructor(constructor);
            // Track @Inject
            if (candidate.hasAnnotation(Inject.class)) {
                if (injected != null) {
                    throw new IllegalArgumentException(
                        "Multiple @Inject constructors found on " + type.getName()
                    );
                }
                injected = candidate;
            }
            // Track max params (fallback)
            if (maxParams == null
                || candidate.parameters().size() > maxParams.parameters().size()) {
                maxParams = candidate;
            }
        }
        return injected != null ? injected : maxParams;
    }

    void initialize(Object instance) {
        if (instance == null) {
            return;
        }
        injectFields(instance);
    }

    private Object[] resolveArguments(List<BeanParameter> parameters) {
        Object[] args = new Object[parameters.size()];
        for (int i = 0; i < parameters.size(); i++) {
            args[i] = resolveParameter(parameters.get(i));
        }
        return args;
    }

    private void injectFields(Object instance) {
        BeanPlan plan = BeanIntrospector.plan(instance.getClass());
        for (BeanProperty property : plan.properties()) {
            if (!property.writable()) {
                continue;
            }
            Object value = resolveMemberValue(property, memberTargetType(property.type()));
            if (value == null) {
                continue;
            }
            try {
                property.write(instance, value);
            } catch (RuntimeException ex) {
                throw new RuntimeException(
                    "Unable to inject field " + property.name() + " on " + instance.getClass().getName(),
                    ex
                );
            }
        }
    }

    private Object resolveParameter(BeanParameter parameter) {
        Type parameterType = parameter.type();
        Class<?> rawType = rawClass(parameterType);
        if (rawType == Container.class) {
            return this;
        }
        if (rawType == SymbolSource.class) {
            return symbolSource;
        }
        if (rawType == Coercer.class) {
            return coercer;
        }
        Object injected = resolveInjectedService(parameter, rawType);
        if (injected != null) {
            return injected;
        }

        ExtensionPoint extensionPoint = parameter.annotation(ExtensionPoint.class);
        if (extensionPoint != null) {
            Class<?> extensionType = extensionPoint.value();
            if (Collection.class.isAssignableFrom(rawType)) {
                return extensionRegistry.values(extensionType);
            }
            if (Map.class.isAssignableFrom(rawType)) {
                return extensionRegistry.mappedValues(extensionType);
            }
        }

        Object configured = resolveConfiguredValue(annotations(parameter), rawType);
        if (configured != null) {
            return configured;
        }

        if (rawType == String.class) {
            return get(String.class);
        }

        return get(rawType);
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterizedType && parameterizedType.getRawType() instanceof Class<?> rawType) {
            return rawType;
        }
        throw new IllegalArgumentException("Unsupported parameter type: " + type.getTypeName());
    }

    private Object resolveMemberValue(BeanProperty property, Class<?> targetType) {
        return resolveMemberValue(annotations(property), targetType);
    }

    private static Class<?> memberTargetType(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterizedType && parameterizedType.getRawType() instanceof Class<?> rawType) {
            return rawType;
        }
        throw new IllegalArgumentException("Unsupported member type: " + type.getTypeName());
    }

    private Object resolveConfiguredValue(BeanParameter parameter, Class<?> targetType) {
        return resolveConfiguredValue(annotations(parameter), targetType);
    }

    private Object resolveMemberValue(AnnotationLookup lookup, Class<?> targetType) {
        Object injected = resolveInjectedService(lookup, targetType);
        if (injected != null) {
            return injected;
        }
        return resolveConfiguredValue(lookup, targetType);
    }

    private Object resolveInjectedService(AnnotationLookup lookup, Class<?> targetType) {
        if (!hasInjectionAnnotation(lookup)) {
            return null;
        }
        if (hasConfiguredValueAnnotation(lookup)) {
            throw new IllegalArgumentException(
                "Cannot combine service injection and configured value annotations on " + lookup
            );
        }
        ServiceId id = resolveServiceId(lookup);
        return id == null ? get(targetType) : get(targetType, id);
    }

    private static boolean hasInjectionAnnotation(AnnotationLookup lookup) {
        return lookup.annotation(Inject.class) != null || lookup.annotation(Named.class) != null;
    }

    private static boolean hasConfiguredValueAnnotation(AnnotationLookup lookup) {
        return lookup.annotation(Symbol.class) != null || lookup.annotation(Value.class) != null;
    }

    private ServiceId resolveServiceId(AnnotationLookup lookup) {
        String injectId = normalizedServiceId(lookup.annotation(Inject.class));
        String namedId = normalizedServiceId(lookup.annotation(Named.class));
        if (injectId != null && namedId != null && !injectId.equals(namedId)) {
            throw new IllegalArgumentException(
                "Conflicting service ids on " + lookup + ": " + injectId + " vs " + namedId
            );
        }
        String value = namedId != null ? namedId : injectId;
        return value == null ? null : ServiceId.of(value);
    }

    private static String normalizedServiceId(Annotation annotation) {
        if (annotation == null) {
            return null;
        }
        String value;
        if (annotation instanceof Inject inject) {
            value = inject.value();
        } else if (annotation instanceof Named named) {
            value = named.value();
        } else {
            return null;
        }
        if (value == null) {
            return null;
        }
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    private Object resolveConfiguredValue(AnnotationLookup lookup, Class<?> targetType) {
        Symbol symbol = lookup.annotation(Symbol.class);
        if (symbol != null) {
            return coerceConfiguredValue(targetType, symbolSource.resolve(symbol.value()), lookup);
        }

        Value value = lookup.annotation(Value.class);
        if (value != null) {
            return coerceConfiguredValue(targetType, symbolSource.expand(value.value()), lookup);
        }

        return null;
    }

    private Object resolveInjectedService(BeanParameter parameter, Class<?> targetType) {
        return resolveInjectedService(annotations(parameter), targetType);
    }

    private Object coerceConfiguredValue(Class<?> targetType, Object rawValue, AnnotationLookup lookup) {
        IntermediateType intermediateType = lookup.annotation(IntermediateType.class);
        Object value = rawValue;
        if (intermediateType != null) {
            value = coercer.coerce(rawValue, intermediateType.value());
        }
        return coercer.coerce(value, targetType);
    }

    private static AnnotationLookup annotations(AnnotatedElement element) {
        return element::getAnnotation;
    }

    private static AnnotationLookup annotations(BeanProperty property) {
        return property::annotation;
    }

    private static AnnotationLookup annotations(BeanParameter parameter) {
        return parameter::annotation;
    }

    @FunctionalInterface
    private interface AnnotationLookup {
        <A extends Annotation> A annotation(Class<A> type);
    }


}
