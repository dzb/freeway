package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.ioc.annotation.Marker;
import com.jujin.freeway.ioc.annotation.NotThreadSafe;
import com.jujin.freeway.ioc.annotation.Primary;
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
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
     * Puts a binding into the index under each of its markers, once, when the
     * binding is registered. Bindings are sealed at registration, so markers
     * added later do not exist. Marker annotations were already validated
     * where they entered the binding
     * ({@link BindingImpl#addMarkers}/{@link BindingImpl#marker}).
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
     * Finds the binding matching the given type and every given marker,
     * selected by the same rule as a lookup by type
     * ({@link BindingIndex#select}). Callers pass at least one marker; an
     * empty marker set matches nothing.
     *
     * @return the selected binding, or {@code null} when none matches
     * @throws com.jujin.freeway.ioc.AmbiguousBindingException if several match
     *         and none is the unique primary
     */
    <T> BindingImpl<T> findByMarker(
        Class<T> type,
        Class<? extends Annotation>[] markers
    ) {
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
        if (matches == null) {
            return null;
        }
        List<BindingImpl<?>> candidates = new ArrayList<>();
        for (BindingImpl<?> binding : matches) {
            if (type.isAssignableFrom(binding.type())) {
                candidates.add(binding);
            }
        }
        return BindingIndex.select(candidates, type, markers);
    }

    /**
     * The markers a module class lists in {@code @Marker} — every binding the
     * module declares inherits them.
     */
    static Set<Class<?>> extractModuleMarkers(Class<?> moduleClass) {
        return Set.copyOf(listedMarkers(moduleClass));
    }

    /**
     * The markers an implementation class declares: those listed in
     * {@code @Marker}, plus the standalone marker annotations placed directly
     * on it ({@code @Primary} and the {@code @NotThreadSafe} concurrency
     * contract — the container rejects a {@code @NotThreadSafe} implementation
     * in a singleton holder).
     */
    static Set<Class<?>> extractClassMarkers(Class<?> implClass) {
        Set<Class<?>> result = listedMarkers(implClass);
        if (implClass.isAnnotationPresent(Primary.class)) {
            result.add(Primary.class);
        }
        if (implClass.isAnnotationPresent(NotThreadSafe.class)) {
            result.add(NotThreadSafe.class);
        }
        return Set.copyOf(result);
    }

    /** The classes {@code type}'s {@code @Marker} lists — validated by the binding they enter. */
    private static Set<Class<?>> listedMarkers(Class<?> type) {
        Marker marker = type.getAnnotation(Marker.class);
        return marker == null ? new HashSet<>() : new HashSet<>(List.of(marker.value()));
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

    /** Releases every indexed binding. */
    void clear() {
        markerToBindings.clear();
    }
}
