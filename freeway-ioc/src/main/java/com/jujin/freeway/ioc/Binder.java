package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.extension.Contributions;
import com.jujin.freeway.ioc.extension.ExtensionPoint;

public interface Binder {

    <T> Binding<T> bind(Class<T> type);

    /**
     * Contribute values to an extension point.
     * <p>
     * The extension point {@code E} must be an interface extending
     * {@link ExtensionPoint}{@code <V>}. The returned {@link Contributions} is typed
     * to the value type {@code V} inferred from the {@code E extends ExtensionPoint<V>}
     * declaration.
     * <p>
     * Usage:
     * <pre>{@code
     * // ExtensionPoint point declaration:
     * public interface CoercionRules extends ExtensionPoint<CoerceRule> {}
     *
     * // Contribution:
     * binder.contribute(CoercionRules.class).add(new CoerceRule<>(...));
     * }</pre>
     *
     * @param point the extension point interface
     * @param <E>   the extension point type, must extend {@link ExtensionPoint}{@code <V>}
     * @param <V>   the value type contributed to this extension point
     * @return a {@link Contributions} builder for the value type
     */
    <E extends ExtensionPoint<V>, V> Contributions<V> contribute(Class<E> point);
}
