package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.extension.ExtensionPoint;
import com.jujin.freeway.ioc.symbol.SymbolProvider;

/**
 * ExtensionPoint point for {@link SymbolProvider} contributions.
 * <p>
 * Contribute:
 * <pre>{@code binder.contribute(SymbolProviders.class).add(provider); }</pre>
 * Consume:
 * <pre>{@code @Inject SymbolProviders providers; }</pre>
 */
public interface SymbolProviders extends ExtensionPoint<SymbolProvider> {}
