package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.commons.coercion.CoerceRule;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.util.Strings;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.extension.Contribution;
import com.jujin.freeway.ioc.extension.Extension;
import com.jujin.freeway.ioc.extension.Ordering;
import com.jujin.freeway.ioc.symbol.SymbolProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The whole contribution story in one place: the write DSL returned by
 * {@code binder.contribute(...)}, the per-extension-point stores, the
 * deferred creation of class/factory contributions, and the composition
 * seal. {@link BinderImpl} declares bindings and delegates contribute here;
 * {@link ContainerImpl} sequences the lifecycle — {@code load (bind) →
 * {@link #drain()} → {@link #seal()} — and hands readers
 * {@link #extension(Class)}.
 *
 * <p>Deferred creations drain in two passes: the config layer
 * ({@link SymbolProvider}, {@link CoerceRule}) first, everything else after.
 * Deferred consumers resolve {@code @Symbol}/{@code @Value} and coerce at
 * construction time, so their providers and rules must already be in place —
 * a single FIFO queue made that depend on declaration order.
 *
 * <p>Config-layer contributions are pulled, not pushed: the container's
 * {@code SymbolSource} and {@code Coercer} consult the extension stores live
 * (see {@link #wire}), so there is no register/replay pipeline and a source
 * replaced by a module reads the same view.
 */
final class ContributionRegistry {

    private final ContainerImpl container;
    private final CoercerDefault coercer;
    private final Map<Class<?>, Extension<?>> extensions = new ConcurrentHashMap<>();

    /** Class/factory contributions of the config layer — drained first. */
    private final List<Runnable> deferredConfig = new ArrayList<>();
    /** Class/factory contributions of every other entry type. */
    private final List<Runnable> deferredEntries = new ArrayList<>();

    /** Set by {@link #seal()}; gates both mutations and future stores. */
    private volatile boolean composed;

    ContributionRegistry(ContainerImpl container, CoercerDefault coercer) {
        this.container = Objects.requireNonNull(container, "container");
        this.coercer = Objects.requireNonNull(coercer, "coercer");
    }

    /** The store for {@code entryType}, created on first touch — sealed on
     *  creation once composition has finished. */
    @SuppressWarnings("unchecked")
    <T> Extension<T> extension(Class<T> entryType) {
        return (Extension<T>) extensions.computeIfAbsent(entryType, k -> {
            Extension<?> ext = new Extension<>(k);
            if (composed) ext.seal();
            return ext;
        });
    }

    /** The write DSL — see {@link Contribution}. */
    <V> Contribution<V> contribute(Class<V> entryType) {
        Extension<V> ext = extension(entryType);
        return new Contribution<>() {
            @Override
            public Contribution<V> add(V value) {
                ext.add(null, value);
                wire(entryType, value);
                return this;
            }

            @Override
            public Ordering add(String id, V value) {
                Ordering handle = ext.add(id, value);
                wire(entryType, value);
                return handle;
            }

            @Override
            public Ordering add(Class<? extends V> implClass) {
                // Canonical id: snake_case_simple_name@package — unique and
                // readable, no dependency on Class.forName. Construction is
                // the factory form below: same deferred phase, one body.
                // The local pins the overload's target type — a bare lambda
                // argument reads as ambiguous (V could be a functional
                // interface) to some compilers.
                String id = Strings.camelToSnake(implClass.getSimpleName())
                    + "@" + implClass.getPackageName();
                Function<Container, ? extends V> factory = c -> c.create(implClass);
                return add(id, factory);
            }

            @Override
            public Ordering add(String id, Function<Container, ? extends V> factory) {
                Objects.requireNonNull(factory, "factory");
                var deferred = new DeferredOrdering();
                defer(entryType, () -> {
                    V instance = Objects.requireNonNull(
                        factory.apply(container),
                        "factory returned null for contribution " + id
                    );
                    Ordering real = ext.add(id, instance);
                    wire(entryType, instance);
                    deferred.apply(real);
                });
                return deferred;
            }
        };
    }

    private void defer(Class<?> entryType, Runnable create) {
        if (isConfigLayer(entryType)) {
            deferredConfig.add(create);
        } else {
            deferredEntries.add(create);
        }
    }

    /**
     * True for entry types the config chain reads: their class contributions
     * must materialize before any other deferred consumer constructs (those
     * resolve {@code @Symbol}/{@code @Value} and coerce while they build).
     * Hard-coded to the two types that have this property — honest for a set
     * of two, no descriptor table to keep in sync.
     */
    private static boolean isConfigLayer(Class<?> entryType) {
        return entryType == SymbolProvider.class
            || entryType == CoerceRule.class;
    }

    /**
     * The one push that remains: a contributed rule lands in the container's
     * coercer. {@code SymbolProvider} needs no counterpart — the source pulls
     * its tiers from the extension store.
     */
    private void wire(Class<?> entryType, Object value) {
        if (entryType == CoerceRule.class) {
            coercer.register((CoerceRule<?, ?>) value);
        }
    }

    /** Creates every deferred contribution: config layer first, then the rest. */
    void drain() {
        for (Runnable create : deferredConfig) {
            create.run();
        }
        deferredConfig.clear();
        for (Runnable create : deferredEntries) {
            create.run();
        }
        deferredEntries.clear();
    }

    /** Closes the contribution window after composition. */
    void seal() {
        composed = true;
        for (Extension<?> ext : extensions.values()) {
            ext.seal();
        }
    }

    /** Drops every store — container close. */
    void clear() {
        extensions.clear();
    }

    /**
     * An Ordering handle that stores ordering constraints and applies
     * them to the real Ordering once the instance is created. Inner (not
     * static) so a post-composition {@code before()}/{@code after()} fails
     * loudly instead of buffering constraints nothing will ever replay.
     */
    private final class DeferredOrdering implements Ordering {
        private final List<String> beforeIds = new ArrayList<>();
        private final List<String> afterIds = new ArrayList<>();

        void apply(Ordering target) {
            if (!beforeIds.isEmpty()) target.before(beforeIds.toArray(new String[0]));
            if (!afterIds.isEmpty()) target.after(afterIds.toArray(new String[0]));
        }

        @Override
        public Ordering before(String... ids) {
            requireComposing("before()");
            for (var id : ids) beforeIds.add(Objects.requireNonNull(id, "id").trim());
            return this;
        }

        @Override
        public Ordering after(String... ids) {
            requireComposing("after()");
            for (var id : ids) afterIds.add(Objects.requireNonNull(id, "id").trim());
            return this;
        }

        private void requireComposing(String op) {
            if (composed) {
                throw new IllegalStateException(
                    "Ordering constraints are accepted only during composition " +
                        "(module bind), before the container is built — " + op +
                        " on a deferred handle after composition would be silently dropped"
                );
            }
        }

    }
}
