package com.jujin.freeway.ioc.extension;

import java.util.List;
import java.util.Map;

/**
 * Read-only view of one extension point.
 *
 * <p>Contributions are added through {@link Contribution} during composition;
 * the container exposes only this read side. A consumer cannot add, seal or
 * otherwise mutate a finished extension, and the compiler says so rather than
 * leaving the write methods available only to throw at runtime.</p>
 *
 * <p>A container-created store is readable only after every contribution
 * declared for its entry type has been published. Reads before that point throw
 * instead of returning a partial view; the read gate is documented on the
 * container and implemented by the internal store.</p>
 *
 * @param <T> the entry type (extension point type)
 */
public interface Extension<T> {

    /** Returns all contributions in insertion/topological order. */
    List<T> all();

    /** Returns named contributions as an immutable id→value map. */
    Map<String, T> asMap();

    /**
     * Fails fast when a required {@code before/after} reference points at an
     * unknown contribution id. Strict consumers call this before resolving so
     * a typo fails startup.
     */
    void validateOrdering();
}
