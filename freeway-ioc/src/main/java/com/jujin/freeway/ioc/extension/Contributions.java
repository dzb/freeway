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
     * order and cannot use {@code before/after}. Returns {@code this} for
     * chaining additional contributions.
     *
     * @param value the contribution value
     * @return this Contributions, for chaining
     */
    Contributions<T> add(T value);

    /**
     * Adds a contribution by implementation class. The container instantiates
     * the class, injects dependencies, and invokes {@code @PostConstruct}.
     * An id is auto-generated from the class simple name via camel-to-snake
     * conversion, enabling {@code before/after} ordering.
     *
     * @param implClass the implementation class
     * @return a Contribution handle for declaring before/after constraints
     */
    Contribution add(Class<? extends T> implClass);

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
