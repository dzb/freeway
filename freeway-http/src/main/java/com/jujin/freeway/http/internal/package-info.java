/**
 * HTTP module internals — <strong>no stability promise</strong> across
 * releases.
 *
 * <p>{@code internal} is part of Freeway, not a visibility gate: it holds
 * the module's ownerless shared helpers ({@code HttpUtils}), assembled by
 * sibling packages across the root and feature packages — they may stay
 * {@code public} for that cross-package assembly, but code outside this
 * module must not depend on their shape. The same "not API" convention
 * applies to the {@code public} types under {@code engine/}: they exist
 * only for sibling-package assembly, because Java has no sub-package
 * visibility.
 */
package com.jujin.freeway.http.internal;
