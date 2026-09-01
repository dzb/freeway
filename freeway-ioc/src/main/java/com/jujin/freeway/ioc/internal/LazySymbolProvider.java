package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.commons.util.LazyValue;
import com.jujin.freeway.ioc.symbol.SymbolProvider;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * On-demand {@link SymbolProvider} facade for class contributions.
 *
 * <p>Registered into the {@code SymbolSource} chain at declaration time (long
 * before the contribution flush), so a consumer declared earlier in the same
 * or a later module can resolve {@code @Value}/{@code @Symbol} regardless of
 * declaration order: the first lookup creates the real provider via the
 * factory, and {@link #force()} (called by the flush) returns the same
 * instance, keeping the extension list and the wired chain consistent.
 *
 * <p>Thread-safety and failure semantics come from {@link LazyValue}: exactly-once
 * creation, a throwing factory propagates and retries on the next access
 * (the failure surfaces during the flush as a startup error).
 */
final class LazySymbolProvider implements SymbolProvider {

    private final LazyValue<SymbolProvider> delegate;

    LazySymbolProvider(Supplier<? extends SymbolProvider> factory) {
        Objects.requireNonNull(factory, "factory");
        this.delegate = LazyValue.of(() -> factory.get());
    }

    @Override
    public String lookup(String name) {
        return delegate.get().lookup(name);
    }

    /** Declared order of the wrapped provider — the facade must not report
     *  the default (last tier) or precedence would silently depend on
     *  contribution order again. */
    @Override
    public int order() {
        return delegate.get().order();
    }

    /** Resolves the real provider, creating it on first access. */
    SymbolProvider force() {
        return delegate.get();
    }
}
