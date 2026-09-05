/**
 * Flow engine internals — <strong>no stability promise</strong> across
 * releases.
 *
 * <p>{@code internal} is part of Freeway, not a visibility gate. This package
 * holds the engine machinery that the root package must keep public only for
 * assembly: {@code Stepper} (the loop-range iterator consumed by the default
 * engine) and {@code FlowContextImpl} (constructed by the root
 * {@code FlowContext} factories). Callers outside the module must not depend
 * on these classes.
 */
package com.jujin.freeway.flow.internal;
