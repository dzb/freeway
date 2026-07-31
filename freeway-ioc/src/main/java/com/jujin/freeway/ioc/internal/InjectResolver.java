package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.commons.bean.BeanIntrospector;
import com.jujin.freeway.commons.bean.BeanParameter;
import com.jujin.freeway.commons.bean.BeanPlan;
import com.jujin.freeway.commons.bean.BeanProperty;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.util.Types;
import com.jujin.freeway.ioc.Scope;
import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.freeway.ioc.annotation.IntermediateType;
import com.jujin.freeway.ioc.annotation.Symbol;
import com.jujin.freeway.ioc.annotation.Value;
import com.jujin.freeway.ioc.extension.Extension;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class InjectResolver {
    private static final Logger LOG = LoggerFactory.getLogger(InjectResolver.class);

    private final ContainerImpl container;

    InjectResolver(ContainerImpl container) {
        this.container = Objects.requireNonNull(container, "container");
    }

    Object[] resolveArguments(Class<?> ownerType, List<BeanParameter> parameters) {
        Object[] args = new Object[parameters.size()];
        for (int i = 0; i < parameters.size(); i++) {
            args[i] = resolveParameter(ownerType, parameters.get(i));
        }
        return args;
    }

    void injectFields(Object instance) {
        Class<?> ownerType = instance.getClass();
        BeanPlan plan = BeanIntrospector.plan(ownerType);
        for (BeanProperty property : plan.properties()) {
            if (!property.isWritable()) {
                continue;
            }
            Object value = resolveValue(
                ownerType,
                annotations(property),
                property.type(),
                Types.rawClass(property.type()),
                false
            );
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

    private Object resolveParameter(Class<?> ownerType, BeanParameter parameter) {
        Type parameterType = parameter.type();
        Class<?> rawType = Types.rawClass(parameterType);
        return resolveValue(ownerType, annotations(parameter), parameterType, rawType, true);
    }

    private static AnnotationLookup annotations(AnnotatedElement element) {
        return new AnnotationLookup() {
            @SuppressWarnings("unchecked")
            public <A extends Annotation> java.util.Optional<A> annotation(Class<A> type) {
                return java.util.Optional.ofNullable(element.getAnnotation(type));
            }

            public Annotation[] annotations() {
                return element.getAnnotations();
            }
        };
    }

    /**
     * Resolves {@code List<Foo>}, {@code Map<String, Foo>}, and
     * {@code Extension<Foo>} from the contribution mechanism.
     * For constructor parameters this fires unconditionally; for fields
     * it requires an {@code @Inject} annotation.
     *
     * <p>{@code Extension<Foo>} is intentionally rejected — inject
     * {@code List<Foo>} or {@code Map<String, Foo>} instead.
     */
    private Object resolveContributed(
        Type memberType,
        Class<?> targetType,
        AnnotationLookup lookup,
        boolean parameterMode
    ) {
        if (!(memberType instanceof ParameterizedType pt)) {
            return null;
        }
        Type[] typeArgs = pt.getActualTypeArguments();
        if (!parameterMode && !hasInjectionAnnotation(lookup)) {
            return null;
        }
        if (targetType == Extension.class) {
            throw new IllegalArgumentException(
                "Extension<V> is not injectable by design. "
                + "Use @Inject List<V> to consume all contributions, "
                + "or @Inject Map<String, V> to consume named contributions by id."
            );
        }
        if (targetType == List.class) {
            if (typeArgs.length < 1 || !(typeArgs[0] instanceof Class<?> entryType)) {
                return null;
            }
            return container.extension(entryType).all();
        }
        if (targetType == Map.class) {
            if (typeArgs.length < 2 || typeArgs[0] != String.class
                || !(typeArgs[1] instanceof Class<?> entryType)) {
                return null;
            }
            return container.extension(entryType).asMap();
        }
        return null;
    }

    private static AnnotationLookup annotations(BeanProperty property) {
        return new AnnotationLookup() {
            @SuppressWarnings("unchecked")
            public <A extends Annotation> java.util.Optional<A> annotation(Class<A> type) {
                return property.annotation(type);
            }

            public Annotation[] annotations() {
                return property.annotations();
            }
        };
    }

    private static AnnotationLookup annotations(BeanParameter parameter) {
        return new AnnotationLookup() {
            @SuppressWarnings("unchecked")
            public <A extends Annotation> java.util.Optional<A> annotation(Class<A> type) {
                return parameter.annotation(type);
            }

            public Annotation[] annotations() {
                return parameter.annotations();
            }
        };
    }

    private Logger resolveLogger(Class<?> ownerType, AnnotationLookup lookup) {
        String id = resolveId(lookup);
        return id == null ? container.loggerSource().get(Objects.requireNonNull(ownerType, "ownerType")) : container.loggerSource().get(id);
    }

    private static boolean hasInjectionAnnotation(AnnotationLookup lookup) {
        return lookup.annotation(Inject.class).isPresent();
    }

    private static boolean hasConfiguredValueAnnotation(AnnotationLookup lookup) {
        return lookup.annotation(Symbol.class).isPresent()
            || lookup.annotation(Value.class).isPresent();
    }

    private String resolveId(AnnotationLookup lookup) {
        return normalizedId(lookup.annotation(Inject.class).orElse(null));
    }

    private Object resolveConfiguredValue(AnnotationLookup lookup, Class<?> targetType) {
        // Resolve SymbolSource/Coercer through the container so a primary
        // override is honored at every injection site (constructor, field,
        // @Value, @Symbol) instead of hard-coding the built-in instance.
        var symbol = lookup.annotation(Symbol.class);
        if (symbol.isPresent()) {
            return coerceConfiguredValue(targetType,
                container.get(SymbolSource.class).resolve(symbol.get().value()), lookup);
        }

        var value = lookup.annotation(Value.class);
        if (value.isPresent()) {
            return coerceConfiguredValue(targetType,
                container.get(SymbolSource.class).expand(value.get().value()), lookup);
        }

        return null;
    }

    private Object coerceConfiguredValue(Class<?> targetType, Object rawValue, AnnotationLookup lookup) {
        var intermediateType = lookup.annotation(IntermediateType.class);
        Object value = rawValue;
        if (intermediateType.isPresent()) {
            value = container.get(Coercer.class).coerce(rawValue, intermediateType.get().value());
        }
        return container.get(Coercer.class).coerce(value, targetType);
    }

    private Object resolveValue(
        Class<?> ownerType,
        AnnotationLookup lookup,
        Type memberType,
        Class<?> targetType,
        boolean parameterMode
    ) {
        if (targetType == Logger.class
                && (parameterMode || hasInjectionAnnotation(lookup))) {
            return resolveLogger(ownerType, lookup);
        }
        // List<Foo> / Map<String, Foo> / Extension<Foo> — resolved from
        // the contribution mechanism. Must precede resolveInjected so @Inject
        // on these types does not attempt a broken container.get(...).
        Object contributed = resolveContributed(memberType, targetType, lookup, parameterMode);
        if (contributed != null) {
            return contributed;
        }
        Object injected = resolveInjected(ownerType, lookup, targetType);
        if (injected != null) {
            return injected;
        }
        Object configured = resolveConfiguredValue(lookup, targetType);
        if (configured != null) {
            return configured;
        }
        if (!parameterMode) {
            return null;
        }
        if (targetType == String.class) {
            return container.get(String.class);
        }
        // Constructor parameters may carry marker annotations without @Inject
        Set<Class<? extends Annotation>> markers = resolveMarkers(lookup);
        Object service;
        if (!markers.isEmpty()) {
            @SuppressWarnings("unchecked")
            Class<? extends Annotation>[] markerArr =
                    markers.toArray(new Class[0]);
            service = container.get(targetType, markerArr);
        } else {
            service = container.get(targetType);
        }
        validateScopeCompatibility(ownerType, targetType, service);
        return service;
    }

    private Object resolveInjected(Class<?> ownerType, AnnotationLookup lookup, Class<?> targetType) {
        if (!hasInjectionAnnotation(lookup)) {
            return null;
        }
        if (hasConfiguredValueAnnotation(lookup)) {
            throw new IllegalArgumentException(
                "Cannot combine service injection and configured value annotations on " + lookup
            );
        }
        if (targetType == Logger.class) {
            String loggerId = resolveId(lookup);
            return loggerId == null ? container.loggerSource().get(ownerType) : container.loggerSource().get(loggerId);
        }
        String id = resolveId(lookup);
        if (id != null) {
            Object service = container.get(targetType, id);
            validateScopeCompatibility(ownerType, targetType, service);
            return service;
        }
        // No explicit id — try marker-based resolution
        Set<Class<? extends Annotation>> markers = resolveMarkers(lookup);
        Object service;
        if (!markers.isEmpty()) {
            @SuppressWarnings("unchecked")
            Class<? extends Annotation>[] markerArr =
                    markers.toArray(new Class[0]);
            service = container.get(targetType, markerArr);
        } else {
            service = container.get(targetType);
        }
        validateScopeCompatibility(ownerType, targetType, service);
        return service;
    }

    /**
     * Scans the injection point for annotations that are known markers.
     * Returns the set of marker annotations found.
     *
     * <p>Annotations that are neither framework annotations nor known markers
     * are ignored with a warning — they may be markers the binding forgot to
     * register via {@code .marker(...)}, which would otherwise resolve the
     * wrong service silently.
     */
    private Set<Class<? extends Annotation>> resolveMarkers(
            AnnotationLookup lookup
    ) {
        Set<Class<? extends Annotation>> result = new HashSet<>();
        for (Annotation ann : lookup.annotations()) {
            Class<? extends Annotation> annType = ann.annotationType();
            // Skip framework annotations that aren't markers
            if (annType == Inject.class || annType == Symbol.class
                    || annType == Value.class || annType == IntermediateType.class) {
                continue;
            }
            // Check if this annotation is a known marker
            if (container.markerIndex().isKnownMarker(annType)) {
                result.add(annType);
            } else {
                LOG.warn(
                    "Ignoring unrecognized annotation {} at an injection point; "
                        + "register it with .marker({}.class) on the binding, "
                        + "or remove it from the injection point",
                    annType.getName(),
                    annType.getSimpleName()
                );
            }
        }
        return result;
    }

    private BindingImpl<?> findOwnerBinding(Class<?> ownerType) {
        BindingImpl<?> exact = container.bindingIndex().findUnique(ownerType);
        if (exact != null) return exact;
        // Check full interface hierarchy (direct + super-interfaces)
        BindingImpl<?> b = findSingletonInterface(ownerType, new HashSet<>());
        if (b != null) return b;
        // Check superclass chain (stop before Object)
        for (Class<?> sup = ownerType.getSuperclass();
             sup != null && sup != Object.class;
             sup = sup.getSuperclass()) {
            b = container.bindingIndex().findUnique(sup);
            if (b != null && b.scope() == Scope.SINGLETON) return b;
        }
        return null;
    }

    private BindingImpl<?> findSingletonInterface(Class<?> type, Set<Class<?>> visited) {
        for (Class<?> iface : type.getInterfaces()) {
            if (!visited.add(iface)) continue;
            BindingImpl<?> b = container.bindingIndex().findUnique(iface);
            if (b != null && b.scope() == Scope.SINGLETON) return b;
            b = findSingletonInterface(iface, visited);
            if (b != null) return b;
        }
        return null;
    }

    private void validateScopeCompatibility(Class<?> ownerType, Class<?> targetType, Object service) {
        if (service == null) {
            return;
        }
        BindingImpl<?> ownerBinding = findOwnerBinding(ownerType);
        if (ownerBinding == null || ownerBinding.scope() != Scope.SINGLETON) {
            return;
        }
        BindingImpl<?> targetBinding = container.bindingIndex().findUnique(targetType);
        if (targetBinding == null || targetBinding.scope() != Scope.THREAD) {
            return;
        }
        if (targetType.isInterface()) {
            return;
        }
        throw new IllegalStateException(
            "Singleton service " + ownerType.getName()
                + " cannot directly inject thread-scoped concrete class "
                + targetType.getName()
                + ". Use an interface with proxy support instead."
        );
    }

    private static String normalizedId(Annotation annotation) {
        if (annotation == null) {
            return null;
        }
        String value;
        if (annotation instanceof Inject inject) {
            value = inject.value();
        } else {
            return null;
        }
        if (value == null) {
            return null;
        }
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    private interface AnnotationLookup {
        <A extends Annotation> java.util.Optional<A> annotation(Class<A> type);

        default Annotation[] annotations() {
            return new Annotation[0];
        }
    }
}
