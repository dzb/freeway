package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.extension.Contributions;

public interface Binder {
    <T> Binding<T> bind(Class<T> type);

    <V> Contributions<V> contribute(Class<V> entryType);

    /**
     * Installs a module. The module's {@link ModuleEx#bind(Binder)} is called
     * immediately, so its services and extensions are registered in the same
     * Installs a nested module during binding. Installing the same module
     * instance twice is a no-op; installing two distinct instances of the
     * same module class fails fast (typically an explicit install plus SPI
     * auto-discovery).
     *
     * @return this binder, for chaining
     */
    Binder install(ModuleEx module);
}
