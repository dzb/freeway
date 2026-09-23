package com.jujin.freeway.ioc.extension;

/**
 * Ordering handle returned by the named {@link Contribution} adds —
 * {@code add(id, value)}, {@code add(id, factory)} and {@code add(Class)}.
 *
 * <p>Declares ordering constraints relative to other named contributions.
 * Unrecognised target ids are ignored with a warning when ordering is
 * evaluated — either the id is a typo, or the contributing module is not
 * installed. Cycles between resolved references still fail.
 */
public interface Ordering {

    /**
     * Declares that this contribution should be ordered before the
     * contributions with the given ids.
     *
     * @param ids the ids this contribution must precede
     * @return this handle for chaining
     */
    Ordering before(String... ids);

    /**
     * Declares that this contribution should be ordered after the
     * contributions with the given ids.
     *
     * @param ids the ids this contribution must follow
     * @return this handle for chaining
     */
    Ordering after(String... ids);

}
