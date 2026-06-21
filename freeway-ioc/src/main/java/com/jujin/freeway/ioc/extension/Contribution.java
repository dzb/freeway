package com.jujin.freeway.ioc.extension;

/**
 * Ordering handle returned by {@link Contributions#add(String, Object)}.
 *
 * <p>Declares ordering constraints relative to other named contributions.
 * Unrecognised target ids are silently ignored.
 */
public interface Contribution {

    /**
     * Declares that this contribution should be ordered before the
     * contributions with the given ids.
     *
     * @param ids the ids this contribution must precede
     * @return this handle for chaining
     */
    Contribution before(String... ids);

    /**
     * Declares that this contribution should be ordered after the
     * contributions with the given ids.
     *
     * @param ids the ids this contribution must follow
     * @return this handle for chaining
     */
    Contribution after(String... ids);
}
