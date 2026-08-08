package com.jujin.freeway.ioc.internal;

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
 * <p>Thread-safe: resolution is volatile double-checked; concurrent lookups
 * create the provider exactly once. A failed factory retries on the next
 * access (the failure surfaces during the flush as a startup error).
 */
final class LazySymbolProvider implements SymbolProvider {

    private final Supplier<? extends SymbolProvider> factory;
    private volatile SymbolProvider resolved;

    LazySymbolProvider(Supplier<? extends SymbolProvider> factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    @Override
    public String lookup(String name) {
        return resolved().lookup(name);
    }

    /** Resolves the real provider, creating it on first access. */
    SymbolProvider force() {
        return resolved();
    }

    private SymbolProvider resolved() {
        SymbolProvider r = resolved;
        if (r == null) {
            synchronized (this) {
                r = resolved;
                if (r == null) {
                    r = factory.get();
                    resolved = r;
                }
            }
        }
        return r;
    }
}
