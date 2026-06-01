package com.jujin.freeway.commons.bean;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class BeanIntrospector {
    private static final ClassValue<BeanPlan> PLANS = new ClassValue<>() {
        @Override
        protected BeanPlan computeValue(Class<?> type) {
            return BeanPlan.of(type);
        }
    };
    private static final ConcurrentMap<Constructor<?>, BeanConstructor> CONSTRUCTORS =
        new ConcurrentHashMap<>();

    private BeanIntrospector() {
    }

    public static BeanPlan plan(Class<?> type) {
        return PLANS.get(Objects.requireNonNull(type, "type"));
    }

    public static BeanConstructor constructor(Constructor<?> constructor) {
        return CONSTRUCTORS.computeIfAbsent(
            Objects.requireNonNull(constructor, "constructor"),
            BeanConstructor::of
        );
    }

    public static BeanConstructor selectConstructor(
        Class<?> type,
        Class<? extends Annotation> preferredAnnotation
    ) throws NoSuchMethodException {
        Constructor<?>[] constructors = Objects.requireNonNull(type, "type").getDeclaredConstructors();
        if (constructors.length == 0) {
            return constructor(type.getDeclaredConstructor());
        }
        BeanConstructor preferred = null;
        BeanConstructor maxParams = null;
        for (Constructor<?> constructor : constructors) {
            BeanConstructor candidate = constructor(constructor);
            if (preferredAnnotation != null && candidate.hasAnnotation(preferredAnnotation)) {
                if (preferred != null) {
                    throw new IllegalArgumentException(
                        "Multiple @" + preferredAnnotation.getSimpleName() + " constructors found on "
                        + type.getName()
                    );
                }
                preferred = candidate;
            }
            if (maxParams == null
                || candidate.parameters().size() > maxParams.parameters().size()) {
                maxParams = candidate;
            }
        }
        return preferred != null ? preferred : maxParams;
    }
}
