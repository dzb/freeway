package com.jujin.freeway.ioc.extension;

/**
 * Extension contribution DSL, returned by {@link com.jujin.freeway.ioc.Binder#contribute(Class)}.
 *
 * <p>Example:
 * <pre>{@code
 * binder.contribute(Route.class)
 *     .add(Route.get("/", ctx -> ctx.send(200, "Hi")))
 *     .add("health", Route.get("/healthz", healthHandler))
 *     .after("freeway.http.server");
 * }</pre>
 *
 * @param <T> the entry type (extension point type)
 */
public interface Contributions<T> {

    /**
     * Adds an unnamed contribution. Contributions are ordered by insertion
     * order and cannot use {@code before/after}.
     *
     * @param value the contribution value
     */
    void add(T value);

    /**
     * Creates and adds an unnamed contribution from its implementation class.
     * The container instantiates the class, injects dependencies, and
     * invokes {@code @PostConstruct} — but does not track the instance.
     * The caller owns the lifecycle.
     *
     * @param implClass the implementation class
     */
    default void create(Class<? extends T> implClass) {
        throw new UnsupportedOperationException(
            "create() requires a Container-based Contributions implementation");
    }

    /**
     * Adds a named contribution with ordering support. Duplicate ids are
     * rejected. Returns a {@link Contribution} handle for declaring
     * ordering constraints.
     *
     * @param id    unique id for ordering
     * @param value the contribution value
     * @return a Contribution handle for declaring before/after constraints
     * @throws IllegalStateException if the id is a duplicate
     */
    Contribution add(String id, T value);
}
