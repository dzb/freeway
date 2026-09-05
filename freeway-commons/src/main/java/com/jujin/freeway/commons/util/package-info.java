/**
 * Small cross-cutting helpers shared across modules.
 *
 * <p>Two kinds of types live here: stateless helpers operating on plain
 * values ({@code Types}, {@code Strings}, {@code Maps}, {@code Digests},
 * {@code ByteStreams}) and shared concurrency/lifecycle primitives —
 * {@code LazyValue} (the framework's lazy-init primitive) and
 * {@code ContextExecutor} (explicit {@code ScopedValue} propagation into
 * worker threads, an app-facing API: the framework itself defaults to no
 * propagation).
 *
 * <p>Commons has no {@code internal} package — non-API helpers are kept
 * {@code package-private} inside the feature package they serve; classes
 * that graduate to this package are public API.
 */
package com.jujin.freeway.commons.util;
