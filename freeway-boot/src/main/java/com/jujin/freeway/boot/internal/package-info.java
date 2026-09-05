/**
 * Boot internals — <strong>no stability promise</strong> across releases.
 *
 * <p>{@code internal} is part of Freeway, not a visibility gate: classes here
 * are public only where a root-package sibling assembles them (e.g.
 * {@code AppBuilder} constructs {@code ConfigLoaderDefault}); code outside
 * this module must not reference them. Note that a {@code XDefault} may live
 * here when substitution never names the class (the {@code AppBuilder}
 * {@code config(ConfigLoader)} override is the replacement path).
 */
package com.jujin.freeway.boot.internal;
