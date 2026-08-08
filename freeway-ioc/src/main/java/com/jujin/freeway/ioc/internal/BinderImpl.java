package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Binding;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.commons.util.Strings;
import com.jujin.freeway.ioc.extension.Contribution;
import com.jujin.freeway.ioc.extension.Contributions;
import com.jujin.freeway.ioc.extension.Extension;
import com.jujin.freeway.ioc.symbol.SymbolProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class BinderImpl implements Binder {
    private final ContainerImpl container;
    private final List<BindingImpl<?>> pending = new ArrayList<>();
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

    /** Registers this module's declared bindings. Called after each module's
     *  {@code bind()} so later modules see earlier ones. */
    void flushPending() {
        for (BindingImpl<?> binding : pending) {
            container.register(binding);
        }
        pending.clear();
    }

    /**
     * Instantiates class contributions ({@code contribute(X).add(Impl.class)}).
     * Deliberately deferred until ALL modules have bound: the contributed class
     * may depend on services declared by a later module, and running the
     * creation at each module's flush would fail on that unregistered binding
     * (module-order coupling).
     *
     * <p>No ordering pass is needed: infrastructure extensions whose consumers
     * may be constructed earlier ({@code SymbolProvider}) are wired on demand
     * at declaration time via a lazy facade (see
     * {@link ContainerImpl#isOnDemandContribution}), so declaration order can
     * never break construction.
     */
    void flushPendingCreates() {
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
                String id = Strings.camelToSnake(implClass.getSimpleName())
                    + "@" + implClass.getPackageName();
                if (container.isOnDemandContribution(entryType)) {
                    // On-demand wiring: register a lazy facade into the built-in
                    // consumer IMMEDIATELY — no ordering pass needed. The real
                    // instance is created on first lookup (e.g. a @Value
                    // resolution in a consumer declared earlier) and reused by
                    // the flush below, so declaration order cannot break
                    // construction.
                    @SuppressWarnings("unchecked")
                    LazySymbolProvider lazy = new LazySymbolProvider(
                        () -> (SymbolProvider) container.create(implClass)
                    );
                    container.wireContribution(entryType, lazy);
                    var deferred = new DeferredContribution();
                    pendingCreates.add(() -> {
                        @SuppressWarnings("unchecked")
                        V instance = (V) lazy.force();
                        Contribution real = ext.add(id, instance);
                        deferred.apply(real);
                    });
                    return deferred;
                }
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
