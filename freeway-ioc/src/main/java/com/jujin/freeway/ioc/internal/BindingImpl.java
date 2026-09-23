package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.ioc.Binding;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Scope;
import com.jujin.freeway.ioc.advisor.Advisor;
import com.jujin.freeway.ioc.annotation.Primary;

import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

final class BindingImpl<T> implements Binding<T> {
    private static final AtomicInteger ID_COUNTER = new AtomicInteger();

    private final ContainerImpl container;
    private final Class<T> type;
    private String id;
    private boolean explicitId;
    private Scope scope = Scope.SINGLETON;
    private Function<Container, ? extends T> provider;
    private final List<AdviceEntry> advices = new ArrayList<>();
    private final Set<Class<?>> markers = new HashSet<>();
    /**
     * Set when the declaring module's bindings are flushed: from then on the
     * binding is registered and immutable — every DSL method below rejects
     * further calls instead of mutating a live container.
     */
    private boolean sealed;

    BindingImpl(ContainerImpl container, Class<T> type) {
        this.container = Objects.requireNonNull(container, "container");
        this.type = Objects.requireNonNull(type, "type");
        this.id = ServiceIds.normalize(type.getSimpleName()) + "@" + ID_COUNTER.getAndIncrement();
    }

    Class<T> type() {
        return type;
    }

    String id() {
        return id;
    }

    Scope scope() {
        return scope;
    }

    /**
     * The {@link Primary} marker is the one source of the verdict:
     * {@code .primary()}, {@code @Primary} on the implementation class and a
     * module-level {@code @Marker(Primary.class)} all land here, so type
     * resolution and marker resolution cannot disagree about it.
     */
    boolean isPrimary() {
        return markers.contains(Primary.class);
    }

    boolean isProxiable() {
        return type.isInterface() && scope != Scope.PROTOTYPE;
    }

    Set<Class<?>> markers() {
        return Collections.unmodifiableSet(markers);
    }

    /**
     * Adds markers directly (module-level propagation, class-declared markers,
     * builtins) — the single place a marker is validated.
     */
    void addMarkers(Set<Class<?>> additional) {
        for (Class<?> m : additional) {
            MarkerIndex.validateMarkerAnnotation(m);
        }
        this.markers.addAll(additional);
    }

    boolean isAdvised() {
        return !advices.isEmpty();
    }

    List<AdviceEntry> advices() {
        return List.copyOf(advices);
    }

    T directInstance() {
        if (provider == null) {
            return instantiateDefault();
        }
        T created = provider.apply(container);
        if (created == null) {
            throw new IllegalStateException(
                "Provider returned null for " + type.getName() + "@" + id
                    + " — bind a non-null value, or throw when the value is"
                    + " legitimately absent (a null would otherwise surface as"
                    + " an anonymous NullPointerException far from here)"
            );
        }
        return materialize(created, this);
    }

    /** Marks the binding registered and immutable — see {@link #sealed}. */
    void seal() {
        sealed = true;
    }

    private void requireOpen(String op) {
        if (sealed) {
            throw new IllegalStateException(
                "Binding for " + type.getName() + " is sealed — " + op
                    + " is accepted only while its module is binding");
        }
    }

    @Override
    public Binding<T> to(Class<? extends T> implementation) {
        requireOpen("to()");
        Class<? extends T> actual = Objects.requireNonNull(implementation, "implementation");
        addMarkers(MarkerIndex.extractClassMarkers(actual));
        return to(ignored -> {
            try {
                return container.constructInstance(actual, this);
            } catch (Exception ex) {
                throw new RuntimeException("Unable to construct " + actual.getName(), ex);
            }
        });
    }

    @Override
    public Binding<T> to(Function<Container, ? extends T> provider) {
        requireOpen("to()");
        this.provider = Objects.requireNonNull(provider, "provider");
        return this;
    }

    @Override
    public Binding<T> scope(Scope scope) {
        requireOpen("scope()");
        this.scope = Objects.requireNonNull(scope, "scope");
        return this;
    }

    @Override
    public Binding<T> id(String id) {
        requireOpen("id()");
        this.id = ServiceIds.normalize(id);
        this.explicitId = true;
        return this;
    }

    boolean hasExplicitId() {
        return explicitId;
    }

    @Override
    public Binding<T> primary() {
        requireOpen("primary()");
        addMarkers(Set.of(Primary.class));
        return this;
    }

    @Override
    public Binding<T> marker(Class<? extends Annotation>... markers) {
        requireOpen("marker()");
        for (Class<? extends Annotation> m : markers) {
            MarkerIndex.validateMarkerAnnotation(m);
            this.markers.add(m);
        }
        return this;
    }

    @Override
    public Binding<T> advise(Consumer<Advisor> advisor) {
        requireOpen("advise()");
        // Advice is applied through a JDK proxy, so only interfaces can carry it.
        // The constraint is known right here — failing at first get() instead
        // pushed a wiring mistake to an unrelated request (and made the message
        // arrive far from the line that caused it).
        if (!type.isInterface()) {
            throw new IllegalArgumentException(
                "Advice is not supported on non-interface type " + type.getName()
                    + " — bind " + type.getName()
                    + " to an interface to use .advise(), or drop the advisor"
            );
        }
        AdvisorImpl builder = new AdvisorImpl();
        Objects.requireNonNull(advisor, "advisor").accept(builder);
        this.advices.addAll(builder.entries());
        return this;
    }

    private T instantiateDefault() {
        if (!type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
            return container.create(type, this);
        }
        throw new IllegalStateException("No implementation configured for " + type.getName());
    }

    private T materialize(T value, BindingImpl<?> owner) {
        try {
            container.initialize(value, owner);
            return value;
        } catch (Exception ex) {
            throw new RuntimeException("Unable to initialize " + type.getName(), ex);
        }
    }
}
