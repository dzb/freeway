package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.extension.Contributions;

public interface Binder {
    <T> Binding<T> bind(Class<T> type);

    <V> Contributions<V> contribute(Class<V> entryType);

    /**
     * Installs a module. The module's {@link ModuleEx#bind(Binder)} is called
     * immediately, so its services and extensions are registered in the same
     * container. Installing the same module type more than once is a no-op
     * (deduplication by {@code module.getClass()}).
     *
     * @return this binder, for chaining
     */
    Binder install(ModuleEx module);
}
