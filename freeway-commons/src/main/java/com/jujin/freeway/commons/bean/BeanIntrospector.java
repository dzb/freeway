package com.jujin.freeway.commons.bean;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandles;
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
 * <p><b>Module-access contract:</b> application classes (unnamed module) are
 * fully introspectable — private constructors, fields and setters are reached
 * via a private lookup. JDK classes live in non-open modules, so only their
 * public members are reachable (via {@link MethodHandles#publicLookup()});
 * {@code BeanPlan.plan} on a JDK type therefore succeeds with a zero-property
 * or non-constructable plan instead of failing. If deeper JDK reflection is
 * ever required, the JVM must be started with the matching
 * {@code --add-opens} flag — the framework does not and cannot do this for
 * the application.
 *
 * <p>Usage:
 * <pre>{@code
 * BeanPlan plan = BeanIntrospector.plan(User.class);
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
        // Atomic check-and-create: a race must not produce two wrappers for
        // the same Constructor.
        return CONSTRUCTORS.computeIfAbsent(constructor, BeanConstructor::of);
    }

    /**
     * Selects the best constructor for the given type.
     * <ul>
     *   <li>If a constructor annotated with {@code preferredAnnotation} exists,
     *       that one is returned (multiple matches throw).</li>
     *   <li>Otherwise the no-arg constructor is returned when present — beans
     *       with convenience constructors must not be auto-wired by parameter
     *       count, since arbitrary parameters are not resolvable services.</li>
     *   <li>Otherwise the constructor with the most parameters is returned
     *       (single-constructor classes without a no-arg constructor).</li>
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
        BeanConstructor noArg = null;
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
            if (constructor.getParameterCount() == 0) {
                noArg = candidate;
            }
            if (maxParams == null
                || candidate.parameters().size() > maxParams.parameters().size()) {
                maxParams = candidate;
            }
        }
        if (preferred != null) {
            return preferred;
        }
        return noArg != null ? noArg : maxParams;
    }
}
