package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Binding;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.ModuleNode;
import com.jujin.freeway.ioc.extension.Contribution;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Binding side of composition: walks the module tree, runs each module's
 * {@code bind}, registers its bindings so later modules see them. The
 * contribution side (DSL, deferral, drain, seal) lives in
 * {@link ContributionRegistry} — this class only delegates {@code contribute}
 * there.
 */
final class BinderImpl implements Binder {
    private static final Logger LOG = LoggerFactory.getLogger(BinderImpl.class);
    private final ContainerImpl container;
    private final ContributionRegistry contributions;
    private final List<BindingImpl<?>> pending = new ArrayList<>();
    private Class<?> currentModule;

    BinderImpl(ContainerImpl container, ContributionRegistry contributions) {
        this.container = Objects.requireNonNull(container, "container");
        this.contributions = Objects.requireNonNull(contributions, "contributions");
    }

    /**
     * Binds the composed tree: each module node is resolved (a class
     * declaration is instantiated here, not at composition) and the bindings
     * it declares are registered right after it, so later modules see earlier
     * ones. Deferred class contributions drain after every module has bound —
     * see {@link ContributionRegistry#drain()} — so a contributed class may
     * depend on services from any module regardless of declaration order.
     */
    void load(ModuleNode tree) {
        for (ModuleNode node : tree.bindOrder()) {
            ModuleEx module = node.resolve();
            LOG.debug("Installing module: {}", module.name());
            currentModule = module.getClass();
            module.bind(this);
            currentModule = null;
            flushPending();
        }
    }

    @Override
    public <T> Binding<T> bind(Class<T> type) {
        BindingImpl<T> binding = new BindingImpl<>(container, type);
        // Propagate module-level markers
        if (currentModule != null) {
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

    @Override
    public <V> Contribution<V> contribute(Class<V> entryType) {
        return contributions.contribute(entryType);
    }

}
