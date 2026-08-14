package com.jujin.freeway.ioc.internal;

import java.util.List;
import java.util.function.Supplier;

interface ProxyFactory {
    <T> T create(Class<T> interfaceType, Supplier<T> provider, String description);

    /**
     * Like {@link #createAdvised}, but when {@code cacheTarget} is set the
     * handler resolves the provider exactly once per proxy and reuses that
     * target for every subsequent invocation. Used for PROTOTYPE targets so a
     * proxy behaves like a single lazily-created instance ("one instance per
     * get(), state persists across calls") instead of creating a fresh target
     * per method call. Must NOT be set for THREAD-scoped targets — their
     * identity is per-scope, not per-proxy.
     */
    <T> T createAdvised(
        Class<T> interfaceType,
        Supplier<T> provider,
        String description,
        List<AdviceEntry> advices,
        boolean cacheTarget
    );
}
