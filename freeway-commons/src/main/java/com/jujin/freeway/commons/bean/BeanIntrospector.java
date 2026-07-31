package com.jujin.freeway.commons.bean;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Cached bean/record introspection engine.
 *
 * <p>Produces {@link BeanPlan} metadata (properties, constructor, annotations)
 * for any class. Results are cached via {@link ClassValue} and
 * {@link ConcurrentHashMap}.
 *
 * <p>Usage:
 * <pre>{@code
 * BeanPlan plan = BeanIntrospector.plan(User.class);
 * plan.properties().forEach(p -> System.out.println(p.name()));
 * Object user = plan.constructor().newInstance("Alice", 30);
 * }</pre>
 */
public final class BeanIntrospector {
    private static final ClassValue<BeanPlan> PLANS = new ClassValue<>() {
        @Override
        protected BeanPlan computeValue(Class<?> type) {
            return BeanPlan.of(type);
        }
    };
    private static final Map<Constructor<?>, BeanConstructor> CONSTRUCTORS =
        Collections.synchronizedMap(new WeakHashMap<>());

    private BeanIntrospector() {
    }

    /**
     * Returns the cached {@link BeanPlan} for the given type.
     *
     * @param type the class to introspect
     * @return the bean plan
     */
    public static BeanPlan plan(Class<?> type) {
        return PLANS.get(Objects.requireNonNull(type, "type"));
    }

    /**
     * Returns a cached {@link BeanConstructor} wrapping a {@link Constructor}.
     *
     * @param constructor the JDK constructor
     * @return the bean constructor handle
     */
    public static BeanConstructor constructor(Constructor<?> constructor) {
        Objects.requireNonNull(constructor, "constructor");
        BeanConstructor cached = CONSTRUCTORS.get(constructor);
        if (cached != null) {
            return cached;
        }
        BeanConstructor created = BeanConstructor.of(constructor);
        CONSTRUCTORS.put(constructor, created);
        return created;
    }

    /**
     * Selects the best constructor for the given type.
     * <ul>
     *   <li>If a constructor annotated with {@code preferredAnnotation} exists,
     *       that one is returned (multiple matches throw).</li>
     *   <li>Otherwise the constructor with the most parameters is returned.</li>
     * </ul>
     *
     * @param type                 the class to inspect
     * @param preferredAnnotation  optional annotation to prefer (e.g. {@code @Inject})
     * @return the best constructor
     * @throws NoSuchMethodException if no constructor is found
     * @throws IllegalArgumentException if multiple annotated constructors exist
     */
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
