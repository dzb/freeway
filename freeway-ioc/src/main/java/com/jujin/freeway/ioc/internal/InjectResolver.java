package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.commons.bean.BeanIntrospector;
import com.jujin.freeway.commons.bean.BeanParameter;
import com.jujin.freeway.commons.bean.BeanPlan;
import com.jujin.freeway.commons.bean.BeanProperty;
import com.jujin.freeway.commons.util.Types;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.extension.Extension;
import com.jujin.freeway.ioc.Scope;
import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.freeway.ioc.annotation.IntermediateType;
import com.jujin.freeway.ioc.annotation.Symbol;
import com.jujin.freeway.ioc.annotation.Value;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import org.slf4j.Logger;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;

final class InjectResolver {
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

    private Object resolveValue(
        Class<?> ownerType,
        AnnotationLookup lookup,
        Type memberType,
        Class<?> targetType,
        boolean parameterMode
    ) {
        if (parameterMode) {
            if (targetType == Container.class) {
                return container;
            }
            if (targetType == SymbolSource.class) {
                return container.symbolSource();
            }
            if (targetType == Coercer.class) {
                return container.coercer();
            }
        }
        if (targetType == Logger.class) {
            return resolveLogger(ownerType, lookup);
        }
        // Extension<Foo> / List<Foo> — resolved from contribution mechanism.
        // Must precede resolveInjected so @Inject on these types does not
        // attempt a broken container.get(Extension.class) / container.get(List.class).
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
        Object service = container.get(targetType);
        validateScopeCompatibility(ownerType, targetType, service);
        return service;
    }

    /**
     * Resolves {@code Extension<Foo>} and {@code List<Foo>} from the contribution
     * mechanism. For constructor parameters this fires unconditionally; for fields
     * it requires an {@code @Inject} annotation.
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
        Type arg = pt.getActualTypeArguments()[0];
        if (!(arg instanceof Class<?> entryType)) {
            return null;
        }
        if (!parameterMode && !hasInjectionAnnotation(lookup)) {
            return null;
        }
        if (targetType == Extension.class) {
            return container.extension(entryType);
        }
        if (targetType == List.class) {
            return container.extension(entryType).all();
        }
        return null;
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
        Object service = id == null ? container.get(targetType) : container.get(targetType, id);
        validateScopeCompatibility(ownerType, targetType, service);
        return service;
    }

    private Logger resolveLogger(Class<?> ownerType, AnnotationLookup lookup) {
        String id = resolveId(lookup);
        return id == null ? container.loggerSource().get(Objects.requireNonNull(ownerType, "ownerType")) : container.loggerSource().get(id);
    }

    private static boolean hasInjectionAnnotation(AnnotationLookup lookup) {
        return lookup.annotation(Inject.class) != null;
    }

    private static boolean hasConfiguredValueAnnotation(AnnotationLookup lookup) {
        return lookup.annotation(Symbol.class) != null || lookup.annotation(Value.class) != null;
    }

    private String resolveId(AnnotationLookup lookup) {
        return normalizedId(lookup.annotation(Inject.class));
    }

    private Object resolveConfiguredValue(AnnotationLookup lookup, Class<?> targetType) {
        Symbol symbol = lookup.annotation(Symbol.class);
        if (symbol != null) {
            return coerceConfiguredValue(targetType, container.symbolSource().resolve(symbol.value()), lookup);
        }

        Value value = lookup.annotation(Value.class);
        if (value != null) {
            return coerceConfiguredValue(targetType, container.symbolSource().expand(value.value()), lookup);
        }

        return null;
    }

    private Object coerceConfiguredValue(Class<?> targetType, Object rawValue, AnnotationLookup lookup) {
        IntermediateType intermediateType = lookup.annotation(IntermediateType.class);
        Object value = rawValue;
        if (intermediateType != null) {
            value = container.coercer().coerce(rawValue, intermediateType.value());
        }
        return container.coercer().coerce(value, targetType);
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

    private void validateScopeCompatibility(Class<?> ownerType, Class<?> targetType, Object service) {
        if (service == null) {
            return;
        }
        BindingImpl<?> ownerBinding = container.bindingIndex().findUnique(ownerType);
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
        <A extends Annotation> A annotation(Class<A> type);
    }
}
