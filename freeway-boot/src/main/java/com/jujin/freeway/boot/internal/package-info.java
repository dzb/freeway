/**
 * Boot internals — <strong>no stability promise</strong> across releases.
 *
 * <p>{@code internal} is part of Freeway, not a visibility gate: classes here
 * are public only where they are assembled across packages (e.g.
 * {@code AppBuilder} calls the static {@code ConfigLoaderImpl.load} and
 * constructs {@code AppRuntimeDefault} and {@code BootModule};
 * {@code AppConfigDefault.of(...)} is the custom-config building block). Per
 * the module guidelines,
 * {@code internal} is a no-stability-promise marker — callers may reference
 * these classes, without any compatibility guarantee.
 */
package com.jujin.freeway.boot.internal;
