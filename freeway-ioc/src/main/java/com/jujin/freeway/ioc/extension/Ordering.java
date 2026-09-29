package com.jujin.freeway.ioc.extension;

/**
 * Ordering handle returned by the named {@link Contribution} adds —
 * {@code add(id, value)}, {@code add(id, factory)} and {@code add(Class)}.
 *
 * <p>Declares ordering constraints relative to other named contributions.
 * A reference through {@link #before}/{@link #after} is required: an unknown
 * id fails {@code validateOrdering} (the boot layer runs it for runtime
 * hooks) and is WARNed and ignored by the lenient {@code all()} path — either
 * the id is a typo, or the contributing module is not installed. A reference
 * through {@link #beforeIfPresent}/{@link #afterIfPresent} is conditional:
 * it orders against the id only when some module contributes it, and is
 * silent when nothing does — for ordering against an optional companion
 * (a migration hook before the HTTP server in an application that may have
 * no server at all). Cycles between resolved references still fail either way.
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

    /**
     * Declares that this contribution should be ordered before the
     * contributions with the given ids, when they exist. An id no module
     * contributes is vacuous, never a failure — not in
     * {@code validateOrdering}, not in {@code all()}.
     *
     * @param ids the ids this contribution precedes when present
     * @return this handle for chaining
     */
    Ordering beforeIfPresent(String... ids);

    /**
     * Declares that this contribution should be ordered after the
     * contributions with the given ids, when they exist. An id no module
     * contributes is vacuous, never a failure — not in
     * {@code validateOrdering}, not in {@code all()}.
     *
     * @param ids the ids this contribution follows when present
     * @return this handle for chaining
     */
    Ordering afterIfPresent(String... ids);

}
