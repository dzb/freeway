/**
 * Cloud wiring internals — <strong>no stability promise</strong> across
 * releases.
 *
 * <p>{@code internal} is part of Freeway, not a visibility gate. Content is
 * restricted to non-replaceable implementation pieces: container-instantiated
 * handlers and filters (kept {@code public} because feature-package modules
 * reference them by class), module-constructed lifecycle hooks, and shared
 * helpers/state ({@code RegistryStore}, {@code ConfigLists}). Replaceable
 * defaults never live here — they sit in their feature packages. Code outside
 * this module must not reference these classes.
 */
package com.jujin.freeway.cloud.internal;
