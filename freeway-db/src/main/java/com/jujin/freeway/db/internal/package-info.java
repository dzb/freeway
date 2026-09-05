/**
 * DB internals — <strong>no stability promise</strong> across releases.
 *
 * <p>{@code internal} is part of Freeway, not a visibility gate: only the two
 * root assemblers ({@code DatabaseBuilder}, {@code DbModule}) construct the
 * classes here, and several stay {@code package-private} (reachable only via
 * their root interfaces). {@code PoolDefault} lives here by design — it is
 * substituted from outside via {@code .primary()} on {@code Pool} and
 * substitution never references the class itself. Code outside this module
 * must not depend on the package's shape across releases.
 */
package com.jujin.freeway.db.internal;
