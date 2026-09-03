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
    private T instance;
    private boolean primary;
    private final List<AdviceEntry> advices = new ArrayList<>();
    private final Set<Class<?>> markers = new HashSet<>();

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

    boolean isPrimary() {
        return primary;
    }

    boolean isProxiable() {
        return type.isInterface() && scope != Scope.PROTOTYPE;
    }

    Set<Class<?>> markers() {
        return Collections.unmodifiableSet(markers);
    }

    /**
     * Adds markers directly (used by BinderImpl for module-level propagation).
     */
    void addMarkers(Set<Class<?>> additional) {
        for (Class<?> m : additional) {
            MarkerIndex.validateMarkerAnnotation(m);
        }
        this.markers.addAll(additional);
    }

    List<AdviceEntry> advices() {
        return List.copyOf(advices);
    }

    T directInstance() {
        if (instance != null) {
            // Instance bindings run the same lifecycle as every other
            // binding: field injection + @PostConstruct at realization, so
            // "has @PreDestroy on close" does not come without "has
            // @PostConstruct on start".
            return materialize(instance);
        }
        if (provider != null) {
            return materialize(provider.apply(container));
        }
        return instantiateDefault();
    }

    @Override
    public Binding<T> to(Class<? extends T> implementation) {
        Class<? extends T> actual = Objects.requireNonNull(implementation, "implementation");
        addMarkers(MarkerIndex.extractClassMarkers(actual));
        setProvider(ignored -> {
            try {
                return container.constructInstance(actual);
            } catch (Error e) {
                throw e;
            } catch (Throwable ex) {
                throw new RuntimeException("Unable to construct " + actual.getName(), ex);
            }
        });
        return this;
    }

    /** Assigns the pre-built instance target. Registration happens later,
     *  when the container registers this binding. */
    void prebuiltInstance(T instance) {
        requireSingletonScope("instance binding");
        setInstance(instance);
    }

    @Override
    public Binding<T> to(Function<Container, ? extends T> provider) {
        setProvider(provider);
        return this;
    }

    @Override
    public Binding<T> scope(Scope scope) {
        Scope next = Objects.requireNonNull(scope, "scope");
        if (instance != null && next != Scope.SINGLETON) {
            throw new IllegalStateException("Instance bindings must use Scope.SINGLETON");
        }
        this.scope = next;
        return this;
    }

    @Override
    public Binding<T> id(String id) {
        String previous = this.id;
        this.id = ServiceIds.normalize(id);
        this.explicitId = true;
        container.updateId(this, previous, this.id);
        return this;
    }

    boolean hasExplicitId() {
        return explicitId;
    }

    @Override
    public Binding<T> primary() {
        this.primary = true;
        this.markers.add(Primary.class);
        container.syncMarkers(this);
        return this;
    }

    @Override
    public Binding<T> marker(Class<? extends Annotation>... markers) {
        for (Class<? extends Annotation> m : markers) {
            MarkerIndex.validateMarkerAnnotation(m);
            this.markers.add(m);
        }
        container.syncMarkers(this);
        return this;
    }

    @Override
    public Binding<T> advise(Consumer<Advisor> advisor) {
        AdvisorImpl builder = new AdvisorImpl();
        Objects.requireNonNull(advisor, "advisor").accept(builder);
        this.advices.addAll(builder.entries());
        return this;
    }

    private T instantiateDefault() {
        if (!type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
            return container.create(type);
        }
        throw new IllegalStateException("No implementation configured for " + type.getName());
    }

    private T materialize(T value) {
        try {
            container.initialize(value);
            return value;
        } catch (Error ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new RuntimeException("Unable to initialize " + type.getName(), ex);
        }
    }
    private void requireSingletonScope(String operation) {
        if (scope != Scope.SINGLETON) {
            throw new IllegalStateException(operation + " requires Scope.SINGLETON");
        }
    }

    private void setProvider(Function<Container, ? extends T> provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.instance = null;
    }

    private void setInstance(T instance) {
        this.instance = Objects.requireNonNull(instance, "instance");
        this.provider = null;
    }
}
