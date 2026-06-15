package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Binding;
import com.jujin.freeway.ioc.Extension;
import com.jujin.freeway.ioc.Module2;
import com.jujin.freeway.ioc.extension.Contribution;
import com.jujin.freeway.ioc.extension.Contributions;

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
    public <V> Contributions<V> contribute(Class<V> entryType) {
        return contribute(entryType, "");
    }

    @Override
    public <V> Contributions<V> contribute(Class<V> entryType, String name) {
        Extension<V> ext = container.extension(entryType, name);
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

    @Override
    public Binder install(Module2 module) {
        Objects.requireNonNull(module, "module");
        container.installModule(module, this);
        return this;
    }
}
