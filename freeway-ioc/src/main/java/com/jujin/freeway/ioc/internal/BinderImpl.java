package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Binding;
import com.jujin.freeway.ioc.extension.Extension;
import com.jujin.freeway.ioc.Module2;
import com.jujin.freeway.ioc.extension.Contribution;
import com.jujin.freeway.ioc.extension.Contributions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class BinderImpl implements Binder {
    private final ContainerImpl container;
    private final List<BindingImpl<?>> pending = new ArrayList<>();
    /** Classes registered via {@link Contributions#create} — instantiated after pending bindings flush. */
    private final List<Runnable> pendingCreates = new ArrayList<>();

    BinderImpl(ContainerImpl container) {
        this.container = Objects.requireNonNull(container, "container");
    }

    @Override
    public <T> Binding<T> bind(Class<T> type) {
        BindingImpl<T> binding = new BindingImpl<>(container, type);
        pending.add(binding);
        return binding;
    }

    void flushPending() {
        for (BindingImpl<?> binding : pending) {
            container.register(binding);
        }
        pending.clear();
        for (var action : pendingCreates) {
            action.run();
        }
        pendingCreates.clear();
    }

    @Override
    public <V> Contributions<V> contribute(Class<V> entryType) {
        Extension<V> ext = container.extension(entryType);
        return new Contributions<>() {
            @Override
            public void add(V value) {
                ext.add(null, value);
            }

            @Override
            public Contribution add(String id, V value) {
                return ext.add(id, value);
            }

            @Override
            public void create(Class<? extends V> implClass) {
                var captureExt = ext;
                pendingCreates.add(() -> {
                    V instance = container.instantiate(implClass);
                    captureExt.add(null, instance);
                });
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
