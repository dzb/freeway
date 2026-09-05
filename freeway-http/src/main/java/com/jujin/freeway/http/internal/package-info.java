/**
 * HTTP module internals — <strong>no stability promise</strong> across
 * releases.
 *
 * <p>{@code internal} is part of Freeway, not a visibility gate: classes here
 * (config snapshot + wire/date helpers + TLS material builders) are
 * assembled by the root {@code HttpModule} and consumed by engine
 * sub-packages — they may stay {@code public} for that sibling-package
 * assembly, but code outside this module must not depend on their shape.
 * The same "not API" convention applies to the other {@code public} types
 * under {@code engine/} — they exist only because Java has no sub-package
 * visibility.
 */
package com.jujin.freeway.http.internal;
