package com.jujin.freeway.ioc.extension;

import java.util.List;

/**
 * An extension point that collects values of type {@code V}.
 * <p>
 * ExtensionPoint points are declared as interfaces extending {@code ExtensionPoint<V>}:
 * <pre>{@code
 * public interface CoercionRules extends ExtensionPoint<CoerceRule> {}
 * public interface HttpFilters   extends ExtensionPoint<HttpFilter> {}
 * }</pre>
 * Modules contribute via {@code binder.contribute(CoercionRules.class).add(...)};
 * consumers inject the extension point and call {@link #all()}:
 * <pre>{@code
 * \@Inject CoercionRules rules;
 * rules.all().forEach(...);
 * }</pre>
 * <p>
 * Values are collected in contribution order (respecting {@code before/after}
 * constraints) and returned as an immutable list.
 *
 * @param <V> the type of values collected by this extension point
 */
public interface ExtensionPoint<V> {

    /**
     * Returns all contributed values in resolved order.
     */
    List<V> all();
}
