package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.extension.ExtensionPoint;

/**
 * ExtensionPoint point for {@link RuntimeHook} contributions.
 * <p>
 * Hooks are collected in order; use {@code .add(id, hook).before(otherId)}
 * to control startup/shutdown sequence.
 */
public interface RuntimeHooks extends ExtensionPoint<RuntimeHook> {}
