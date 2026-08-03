package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Binding;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.extension.Contribution;
import com.jujin.freeway.ioc.extension.Contributions;
import com.jujin.freeway.ioc.extension.Extension;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class BinderImpl implements Binder {
    private final ContainerImpl container;
    private final List<BindingImpl<?>> pending = new ArrayList<>();
    /** Classes registered via {@link Contributions#create} — instantiated after pending bindings flush. */
    private final List<Runnable> pendingCreates = new ArrayList<>();
    private Class<?> currentModule;

    BinderImpl(ContainerImpl container) {
        this.container = Objects.requireNonNull(container, "container");
    }

    /**
     * Sets the module class currently being processed. Called by
     * {@link ContainerImpl#installModule} before {@code module.bind()}.
     */
    void setCurrentModule(Class<?> moduleClass) {
        this.currentModule = moduleClass;
    }

    void restoreCurrentModule(Class<?> previous) {
        this.currentModule = previous;
    }

    Class<?> currentModule() {
        return currentModule;
    }

    @Override
    public <T> Binding<T> bind(Class<T> type) {
        BindingImpl<T> binding = new BindingImpl<>(container, type);
        // Propagate module-level markers
        if (currentModule != null) {
            binding.setSourceModule(currentModule);
            Set<Class<?>> moduleMarkers = MarkerIndex.extractModuleMarkers(currentModule);
            if (!moduleMarkers.isEmpty()) {
                binding.addMarkers(moduleMarkers);
            }
        }
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
            public Contributions<V> add(V value) {
                ext.add(null, value);
                container.wireContribution(entryType, value);
                return this;
            }

            @Override
            public Contribution add(String id, V value) {
                Contribution handle = ext.add(id, value);
                container.wireContribution(entryType, value);
                return handle;
            }

            @Override
            public Contribution add(Class<? extends V> implClass) {
                // Canonical id: snake_case_simple_name@package — unique and readable,
                // no dependency on Class.forName.
                String id = com.jujin.freeway.commons.util.Strings.camelToSnake(
                    implClass.getSimpleName()) + "@" + implClass.getPackageName();
                var deferred = new DeferredContribution();
                pendingCreates.add(() -> {
                    V instance = container.create(implClass);
                    Contribution real = ext.add(id, instance);
                    container.wireContribution(entryType, instance);
                    deferred.apply(real);
                });
                return deferred;
            }
        };
    }

    @Override
    public Binder install(ModuleEx module) {
        Objects.requireNonNull(module, "module");
        container.installModule(module, this);
        return this;
    }

    /**
     * A Contribution handle that stores ordering constraints and applies
     * them to the real Contribution once the instance is created.
     */
    private static final class DeferredContribution implements Contribution {
        private final List<String> beforeIds = new ArrayList<>();
        private final List<String> afterIds = new ArrayList<>();

        void apply(Contribution target) {
            if (!beforeIds.isEmpty()) target.before(beforeIds.toArray(new String[0]));
            if (!afterIds.isEmpty()) target.after(afterIds.toArray(new String[0]));
        }

        @Override
        public Contribution before(String... ids) {
            for (var id : ids) beforeIds.add(Objects.requireNonNull(id, "id").trim());
            return this;
        }

        @Override
        public Contribution after(String... ids) {
            for (var id : ids) afterIds.add(Objects.requireNonNull(id, "id").trim());
            return this;
        }

    }

}
