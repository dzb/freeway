package com.jujin.freeway2.ioc.internal;

import com.jujin.freeway2.ioc.Binding;
import com.jujin.freeway2.ioc.Container;
import com.jujin.freeway2.ioc.ServiceId;
import com.jujin.freeway2.ioc.Scope;
import com.jujin.freeway2.ioc.advisor.Advisor;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.Objects;
import java.lang.reflect.Modifier;

final class BindingImpl<T> implements Binding<T> {
    private final ContainerImpl container;
    private final Class<T> type;
    private ServiceId id;
    private Scope scope = Scope.SINGLETON;
    private Function<Container, ? extends T> provider;
    private T instance;
    private boolean primary;
    private final List<AdviceEntry> advices = new ArrayList<>();

    BindingImpl(ContainerImpl container, Class<T> type) {
        this.container = Objects.requireNonNull(container, "container");
        this.type = Objects.requireNonNull(type, "type");
        this.id = ServiceId.of(type.getSimpleName());
    }

    Class<T> type() {
        return type;
    }

    ServiceId id() {
        return id;
    }

    Scope scope() {
        return scope;
    }

    boolean isPrimary() {
        return primary;
    }

    boolean isProxiable() {
        return type.isInterface() && scope != Scope.PROTOTYPE && (provider != null || !advices.isEmpty());
    }

    List<AdviceEntry> advices() {
        return List.copyOf(advices);
    }

    Object directInstance() {
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
        this.provider = ignored -> {
            try {
                return container.constructType(actual);
            } catch (Throwable ex) {
                throw new RuntimeException("Unable to construct " + actual.getName(), ex);
            }
        };
        this.instance = null;
        return this;
    }

    @Override
    public Binding<T> to(T instance) {
        this.instance = Objects.requireNonNull(instance, "instance");
        this.provider = null;
        this.scope = Scope.SINGLETON;
        return this;
    }

    @Override
    public Binding<T> to(Function<Container, ? extends T> provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.instance = null;
        return this;
    }

    @Override
    public Binding<T> scope(Scope scope) {
        this.scope = Objects.requireNonNull(scope, "scope");
        return this;
    }

    @Override
    public Binding<T> id(ServiceId id) {
        ServiceId previous = this.id;
        this.id = Objects.requireNonNull(id, "id");
        container.updateBindingId(this, previous, this.id);
        return this;
    }

    @Override
    public Binding<T> primary() {
        this.primary = true;
        return this;
    }

    @Override
    public Binding<T> advise(java.util.function.Consumer<Advisor> advisor) {
        AdvisorImpl builder = new AdvisorImpl();
        Objects.requireNonNull(advisor, "advisor").accept(builder);
        this.advices.addAll(builder.entries());
        return this;
    }

    private Object instantiateDefault() {
        if (!type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
            return container.instantiateType(type);
        }
        throw new IllegalStateException("No implementation configured for " + type.getName());
    }

    private T materialize(T value) {
        container.initialize(value);
        return value;
    }
}
