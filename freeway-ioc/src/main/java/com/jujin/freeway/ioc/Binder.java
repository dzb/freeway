package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.extension.Contribution;

/**
 * Composition DSL handed to every {@link ModuleEx#bind(Binder)}: declare a
 * service with {@link #bind(Class)}, or contribute values to an extension point
 * (hooks, providers, filters, subscribers) with {@link #contribute(Class)}.
 */
public interface Binder {
    <T> Binding<T> bind(Class<T> type);

    <V> Contribution<V> contribute(Class<V> entryType);
}
