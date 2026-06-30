package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.ioc.Binding;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Scope;
import com.jujin.freeway.ioc.advisor.Advisor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.Objects;
import java.lang.reflect.Modifier;

final class BindingImpl<T> implements Binding<T> {
    private static final AtomicInteger ID_COUNTER = new AtomicInteger();

    private final ContainerImpl container;
    private final Class<T> type;
    private String id;
    private Scope scope = Scope.SINGLETON;
    private Function<Container, ? extends T> provider;
    private T instance;
    private boolean primary;
    private final List<AdviceEntry> advices = new ArrayList<>();

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

    List<AdviceEntry> advices() {
        return List.copyOf(advices);
    }

    T directInstance() {
        if (instance != null) {
            return instance;
        }
        if (provider != null) {
            return materialize(provider.apply(container));
        }
        return instantiateDefault();
    }

    @Override
    public Binding<T> to(Class<? extends T> implementation) {
        Class<? extends T> actual = Objects.requireNonNull(implementation, "implementation");
        setProvider(ignored -> {
            try {
                return container.construct(actual);
            } catch (Error e) { throw e; } catch (Throwable ex) {
                throw new RuntimeException("Unable to construct " + actual.getName(), ex);
            }
        });
        return this;
    }

    @Override
    public Binding<T> to(T instance) {
        requireSingletonScope("instance binding");
        setInstance(instance);
        return this;
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
        container.updateId(this, previous, this.id);
        return this;
    }

    @Override
    public Binding<T> primary() {
        this.primary = true;
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
