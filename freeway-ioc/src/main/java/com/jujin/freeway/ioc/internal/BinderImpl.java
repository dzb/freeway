package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Binding;
import com.jujin.freeway.ioc.extension.Contribution;
import com.jujin.freeway.ioc.extension.Contributions;
import com.jujin.freeway.ioc.extension.ExtensionPoint;

import java.util.Objects;

final class BinderImpl implements Binder {
    private final ContainerImpl container;

    BinderImpl(ContainerImpl container) {
        this.container = Objects.requireNonNull(container, "container");
    }

    @Override
    public <T> Binding<T> bind(Class<T> type) {
        BindingImpl<T> binding = new BindingImpl<>(container, type);
        container.register(binding);
        return binding;
    }

    @Override
    public <E extends ExtensionPoint<V>, V> Contributions<V> contribute(Class<E> point) {
        @SuppressWarnings("unchecked")
        ExtensionProxy ext = container.extension(point);
        return new Contributions<>() {
            @Override
            public void add(V value) {
                ext.add(null, value);
            }

            @Override
            public Contribution add(String id, V value) {
                return ext.add(id, value);
            }
        };
    }
}
