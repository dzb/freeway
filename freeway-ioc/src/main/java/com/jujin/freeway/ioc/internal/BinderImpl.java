package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Binding;
import com.jujin.freeway.ioc.extension.Contributions;
import com.jujin.freeway.ioc.extension.MappedContributions;
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
    public <T> Contributions<T> contribute(Class<T> valueType) {
        return container.extensionRegistry().contributions(valueType);
    }

    @Override
    public <V> MappedContributions<V> contributeMapped(Class<V> valueType) {
        return container.extensionRegistry().mapped(valueType);
    }
}
