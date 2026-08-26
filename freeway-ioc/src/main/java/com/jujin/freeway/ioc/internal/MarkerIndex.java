package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.ioc.AmbiguousBindingException;
import com.jujin.freeway.ioc.annotation.Marker;
import com.jujin.freeway.ioc.annotation.NotThreadSafe;
import com.jujin.freeway.ioc.annotation.Primary;
import com.jujin.freeway.ioc.annotation.ThreadSafe;
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Maintains a reverse index from marker annotation classes to bindings
 * that carry those markers. Supports resolving services by the intersection
 * of type and marker annotations.
 *
 * <p>Matching uses {@code containsAll} semantics: a binding matches when
 * its marker set contains every marker requested at the injection point.
 */
final class MarkerIndex {

    private final Map<Class<?>, List<BindingImpl<?>>> markerToBindings = new ConcurrentHashMap<>();

    /**
     * Returns true if the given class is a known marker annotation.
     */
    boolean isKnownMarker(Class<? extends Annotation> annotationType) {
        return markerToBindings.containsKey(annotationType);
    }

    /**
     * Registers a binding in the marker index. Called after the binding
     * is fully configured (markers populated).
     */
    void register(BindingImpl<?> binding) {
        Set<Class<?>> markers = binding.markers();
        if (markers.isEmpty()) {
            return;
        }
        for (Class<?> marker : markers) {
            validateMarkerAnnotation(marker);
            markerToBindings.computeIfAbsent(marker, k -> new CopyOnWriteArrayList<>()).add(binding);
        }
    }

    /**
     * Adds markers that were declared on an already-registered binding
     * (via {@code .marker(...)}/{@code .primary()} after flush). Does not
     * duplicate entries; removing markers is not supported.
     */
    void sync(BindingImpl<?> binding) {
        for (Class<?> marker : binding.markers()) {
            List<BindingImpl<?>> bindings =
                markerToBindings.computeIfAbsent(marker, k -> new CopyOnWriteArrayList<>());
            if (!bindings.contains(binding)) {
                bindings.add(binding);
            }
        }
    }

    /**
     * Finds the unique binding matching the given type and marker set.
     * Returns {@code null} if no markers are provided (caller should fall
     * back to normal type-based resolution).
     *
     * @param type    the requested service type
     * @param markers the marker annotations present at the injection point
     * @param <T>     the service type
     * @return the unique matching binding, or null
     * @throws IllegalArgumentException if multiple bindings match and none is primary
     */
    @SuppressWarnings("unchecked")
    <T> BindingImpl<T> findByMarker(
        Class<T> type,
        Class<? extends Annotation>[] markers
    ) {
        if (markers.length == 0) {
            return null;
        }

        // Start with all bindings assignable to the requested type
        Set<BindingImpl<?>> matches = null;

        for (Class<? extends Annotation> marker : markers) {
            List<BindingImpl<?>> bindings = markerToBindings.get(marker);
            if (bindings == null) {
                return null; // No binding has this marker at all
            }
            if (matches == null) {
                matches = new HashSet<>(bindings);
            } else {
                matches.retainAll(bindings);
            }
            if (matches.isEmpty()) {
                return null;
            }
        }

        if (matches == null || matches.isEmpty()) {
            return null;
        }

        // Filter by type assignability
        List<BindingImpl<?>> typeMatches = new ArrayList<>();
        for (BindingImpl<?> binding : matches) {
            if (type.isAssignableFrom(binding.type())) {
                typeMatches.add(binding);
            }
        }

        if (typeMatches.isEmpty()) {
            return null;
        }
        if (typeMatches.size() == 1) {
            return (BindingImpl<T>) typeMatches.getFirst();
        }

        // Multiple matches — check for primary
        BindingImpl<?> primary = null;
        for (BindingImpl<?> b : typeMatches) {
            if (b.isPrimary()) {
                if (primary != null) {
                    throw new AmbiguousBindingException(
                        "Multiple primary services match type " + type.getName()
                            + " with markers " + markerNames(markers)
                    );
                }
                primary = b;
            }
        }
        if (primary != null) {
            return (BindingImpl<T>) primary;
        }

        throw new AmbiguousBindingException(
            "Multiple services match type " + type.getName()
                + " with markers " + markerNames(markers)
                + "; mark one binding as primary()"
        );
    }

    /**
     * Extracts markers from a module class. Reads {@code @Marker} annotation
     * and returns the listed classes. Returns empty set if the module has no
     * {@code @Marker} annotation.
     */
    static Set<Class<?>> extractModuleMarkers(Class<?> moduleClass) {
        Marker marker = moduleClass.getAnnotation(Marker.class);
        if (marker == null || marker.value().length == 0) {
            return Collections.emptySet();
        }
        Set<Class<?>> result = new HashSet<>();
        for (Class<?> c : marker.value()) {
            validateMarkerAnnotation(c);
            result.add(c);
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * Extracts markers from an implementation class. Reads {@code @Marker}
     * and standalone marker annotations (like {@code @Primary}).
     */
    static Set<Class<?>> extractClassMarkers(Class<?> implClass) {
        Set<Class<?>> result = new HashSet<>();
        Marker marker = implClass.getAnnotation(Marker.class);
        if (marker != null) {
            for (Class<?> c : marker.value()) {
                validateMarkerAnnotation(c);
                result.add(c);
            }
        }
        // Also pick up @Primary on the class
        if (implClass.getAnnotation(Primary.class) != null) {
            result.add(Primary.class);
        }
        // Concurrency-contract markers (same direct-annotation style as
        // @Primary): the container rejects @NotThreadSafe into a singleton.
        boolean threadSafe = implClass.getAnnotation(ThreadSafe.class) != null;
        boolean notThreadSafe = implClass.getAnnotation(NotThreadSafe.class) != null;
        if (threadSafe && notThreadSafe) {
            throw new IllegalArgumentException(
                "Implementation " + implClass.getName()
                    + " is annotated with both @ThreadSafe and @NotThreadSafe");
        }
        if (threadSafe) {
            result.add(ThreadSafe.class);
        }
        if (notThreadSafe) {
            result.add(NotThreadSafe.class);
        }
        return Collections.unmodifiableSet(result);
    }

    static void validateMarkerAnnotation(Class<?> markerClass) {
        Retention retention = markerClass.getAnnotation(Retention.class);
        if (retention != null && retention.value() == RetentionPolicy.RUNTIME) {
            return;
        }
        throw new IllegalArgumentException(
            "Marker annotation " + markerClass.getName()
                + " must have @Retention(RetentionPolicy.RUNTIME)"
        );
    }

    private static String markerNames(Class<? extends Annotation>[] markers) {
        if (markers.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < markers.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('@').append(markers[i].getSimpleName());
        }
        return sb.append(']').toString();
    }

}
