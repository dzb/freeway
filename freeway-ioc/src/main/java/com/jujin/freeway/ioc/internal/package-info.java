/**
 * IoC container internals — <strong>no stability promise</strong> across
 * releases.
 *
 * <p>{@code internal} is part of Freeway, not a visibility gate: classes here
 * may stay {@code public} because sibling packages assemble them (the root
 * package constructs {@code ContainerImpl} etc.), but code outside this
 * module must not reference them. Callers that do depend on their shape
 * across releases do so at their own risk.
 */
package com.jujin.freeway.ioc.internal;
