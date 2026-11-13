package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.commons.coercion.CoerceRule;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.util.Strings;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.RuntimeDescription;
import com.jujin.freeway.ioc.extension.Contribution;
import com.jujin.freeway.ioc.extension.ExtensionStore;
import com.jujin.freeway.ioc.extension.Ordering;
import com.jujin.freeway.ioc.extension.OrderingSupport;
import com.jujin.freeway.ioc.symbol.SymbolProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The whole contribution story in one place: the write DSL returned by
 * {@code binder.contribute(...)}, the per-extension-point stores, the
 * deferred creation of class/factory contributions, and the composition
 * seal. {@link BinderImpl} declares bindings and delegates contribute here;
 * {@link ContainerImpl} sequences the lifecycle — {@code load (bind) →
 * {@link #drain()} → {@link #seal()} — and hands readers
 * {@link #extension(Class)}.
 *
 * <p>Deferred creations are published in two stages. The config layer
 * ({@link SymbolProvider}, {@link CoerceRule}) is constructed, published and
 * initialized first, because its providers and rules must be final before any
 * consumer is constructed. Every other entry is then constructed and published;
 * only after that does the container seal and run field injection plus
 * {@code @PostConstruct}. A service realized from a field-injection chain
 * therefore sees the complete extension view, never the prefix that happened
 * to be drained.
 *
 * <p>Config-layer contributions are pulled, not pushed: the container's
 * {@code SymbolSource} and {@code Coercer} consult the extension stores live
 * (see {@link #wire}), so there is no register/replay pipeline and a source
 * replaced by a module reads the same view. Those two entry types are exempt
 * from the completeness gate for the same reason.
 */
final class ContributionRegistry {

    private final ContainerImpl container;
    private final CoercerDefault coercer;
    private final Map<Class<?>, ExtensionStore<?>> extensions = new ConcurrentHashMap<>();

    /** Class/factory contributions of the config layer — published first. */
    private final List<Deferred> deferredConfig = new ArrayList<>();
    /** Class/factory contributions of every other entry type. */
    private final List<Deferred> deferredEntries = new ArrayList<>();
    /** Entry types published before all others — the config chain's inputs. */
    private final Set<Class<?>> earlyTypes;
    /** Declared-but-unpublished deferred entries per type; the read gate. */
    private final Map<Class<?>, AtomicInteger> pending = new ConcurrentHashMap<>();

    /** Set by {@link #seal()}; gates both mutations and future stores. */
    private volatile boolean composed;

    /**
     * One deferred contribution. Construction is separated from
     * initialization so every value can be published before any field
     * injection or {@code @PostConstruct} runs.
     */
    private static final class Deferred {
        private final Class<?> entryType;
        private final Supplier<Object> construct;
        private final Consumer<Object> publish;
        private final Consumer<Object> initialize;
        private Object value;

        private Deferred(Class<?> entryType, Supplier<Object> construct,
                         Consumer<Object> publish, Consumer<Object> initialize) {
            this.entryType = entryType;
            this.construct = construct;
            this.publish = publish;
            this.initialize = initialize;
        }
    }

    ContributionRegistry(ContainerImpl container, CoercerDefault coercer, Set<Class<?>> earlyTypes) {
        this.container = Objects.requireNonNull(container, "container");
        this.coercer = Objects.requireNonNull(coercer, "coercer");
        // The entry types whose deferred creations are constructed, published and
        // initialized before all others — the config chain's inputs, named by the
        // assembler that owns both sides (the container builds the chain from these
        // same extension points). A closed set by construction: a new chain input is
        // a new chain, edited here at the call site, not a row in this registry.
        this.earlyTypes = Set.copyOf(Objects.requireNonNull(earlyTypes, "earlyTypes"));
    }

    /** The store for {@code entryType}, created on first touch — sealed on
     *  creation once composition has finished. */
    @SuppressWarnings("unchecked")
    <T> ExtensionStore<T> extension(Class<T> entryType) {
        return (ExtensionStore<T>) extensions.computeIfAbsent(entryType, k -> {
            ExtensionStore<?> ext = new ExtensionStore<>(k, () -> complete(k));
            if (composed) ext.seal();
            return ext;
        });
    }

    /**
     * Metadata for every extension point that exists in this container.
     * Sorted by entry type so the description is stable across runs; no store
     * is created merely to describe it, and no service is realized.
     */
    List<RuntimeDescription.ExtensionDescription> describe() {
        return extensions.values().stream()
            .map(ExtensionStore::describe)
            .sorted((left, right) -> left.entryType().compareTo(right.entryType()))
            .toList();
    }

    /** Whether every declared contribution of {@code entryType} has landed. */
    private boolean complete(Class<?> entryType) {
        if (earlyTypes.contains(entryType)) {
            return true;
        }
        AtomicInteger count = pending.get(entryType);
        return count == null || count.get() == 0;
    }

    /** The write DSL — see {@link Contribution}. */
    <V> Contribution<V> contribute(Class<V> entryType) {
        ExtensionStore<V> ext = extension(entryType);
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
            @SuppressWarnings("unchecked")
            public Ordering addDeferred(Class<? extends V> implClass) {
                // Canonical id: snake_case_simple_name@package — unique and
                // readable, no dependency on Class.forName. Construction is
                // the factory form below: same deferred phase, one body.
                String id = Strings.camelToSnake(implClass.getSimpleName())
                    + "@" + implClass.getPackageName();
                boolean[] applied = {false};
                OrderingSupport ordering = deferredOrdering(applied);
                // Construction only. Field injection and @PostConstruct run
                // after every contribution has been published, so a constructor
                // that consumes a collection injection point is rejected by the
                // completeness gate instead of freezing a prefix.
                Supplier<Object> construct = () -> {
                    try {
                        return container.constructInstance(implClass, null);
                    } catch (RuntimeException ex) {
                        throw ex;
                    } catch (Exception ex) {
                        throw new RuntimeException(
                            "Unable to instantiate " + implClass.getName(), ex);
                    }
                };
                Consumer<Object> publish = instance -> {
                    Ordering real = ext.add(id, (V) instance);
                    wire(entryType, instance);
                    ordering.applyTo(real);
                    applied[0] = true;
                };
                Consumer<Object> initialize = instance ->
                    container.initialize(instance, null);
                defer(entryType, construct, publish, initialize);
                return ordering;
            }

            @Override
            @SuppressWarnings("unchecked")
            public Ordering addDeferred(String id, Function<Container, ? extends V> factory) {
                Objects.requireNonNull(factory, "factory");
                boolean[] applied = {false};
                OrderingSupport ordering = deferredOrdering(applied);
                Supplier<Object> construct = () -> Objects.requireNonNull(
                    factory.apply(container),
                    "factory returned null for contribution " + id
                );
                Consumer<Object> publish = instance -> {
                    Ordering real = ext.add(id, (V) instance);
                    wire(entryType, instance);
                    ordering.applyTo(real);
                    applied[0] = true;
                };
                defer(entryType, construct, publish, null);
                return ordering;
            }
        };
    }

    private void defer(Class<?> entryType, Supplier<Object> construct,
                       Consumer<Object> publish, Consumer<Object> initialize) {
        pending.computeIfAbsent(entryType, k -> new AtomicInteger()).incrementAndGet();
        Deferred deferred = new Deferred(entryType, construct, publish, initialize);
        if (earlyTypes.contains(entryType)) {
            deferredConfig.add(deferred);
        } else {
            deferredEntries.add(deferred);
        }
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

    /** Constructs and publishes the config layer; no lifecycle callbacks yet. */
    void constructConfig() {
        construct(deferredConfig);
    }

    /** Runs field injection and {@code @PostConstruct} for the config layer. */
    void initializeConfig() {
        initialize(deferredConfig);
        deferredConfig.clear();
    }

    /** Constructs and publishes every remaining deferred contribution. */
    void constructEntries() {
        construct(deferredEntries);
    }

    /** Runs field injection and {@code @PostConstruct} for every entry. */
    void initializeEntries() {
        initialize(deferredEntries);
        deferredEntries.clear();
    }

    private void construct(List<Deferred> deferred) {
        for (Deferred entry : deferred) {
            entry.value = entry.construct.get();
            entry.publish.accept(entry.value);
            pending.get(entry.entryType).decrementAndGet();
        }
    }

    private void initialize(List<Deferred> deferred) {
        for (Deferred entry : deferred) {
            if (entry.initialize != null) {
                entry.initialize.accept(entry.value);
            }
        }
    }

    /** Closes the contribution window after composition. */
    void seal() {
        composed = true;
        for (ExtensionStore<?> ext : extensions.values()) {
            ext.seal();
        }
    }

    /** Drops every store and deferred queue — container close. */
    void clear() {
        extensions.clear();
        pending.clear();
        deferredConfig.clear();
        deferredEntries.clear();
    }

    /**
     * The deferred form's lifecycle gate: constraints remain mutable while
     * composition is open and the instance has not landed. Once either line is
     * crossed, a constraint would be buffered with no replay coming, so it
     * fails at the fluent call instead.
     */
    private OrderingSupport deferredOrdering(boolean[] applied) {
        return new OrderingSupport(op -> requireDeferredMutable(op, applied[0]));
    }

    private void requireDeferredMutable(String op, boolean applied) {
        if (composed) {
            throw new IllegalStateException(
                "Ordering constraints are accepted only during composition " +
                    "(module bind), before the container is built — " + op +
                    " on a deferred handle after composition would be silently dropped"
            );
        }
        if (applied) {
            throw new IllegalStateException(
                "Ordering constraints on a deferred handle are accepted only " +
                    "before its contribution is created — " + op +
                    " after the instance landed has nothing left to replay into"
            );
        }
    }
}
