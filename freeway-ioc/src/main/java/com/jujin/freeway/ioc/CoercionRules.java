package com.jujin.freeway.ioc;

import com.jujin.freeway.commons.coercion.CoerceRule;
import com.jujin.freeway.ioc.extension.ExtensionPoint;

/**
 * ExtensionPoint point for {@link CoerceRule} contributions.
 * <p>
 * Contribute:
 * <pre>{@code binder.contribute(CoercionRules.class).add(new CoerceRule<>(...)); }</pre>
 * Consume:
 * <pre>{@code @Inject CoercionRules rules; }</pre>
 */
public interface CoercionRules extends ExtensionPoint<CoerceRule<?, ?>> {}
