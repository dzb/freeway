package com.jujin.freeway.ioc.extension;

import com.jujin.freeway.ioc.Container;

import java.util.function.Function;

/**
 * Extension contribution DSL, returned by {@link com.jujin.freeway.ioc.Binder#contribute(Class)}.
 *
 * <p>The container accepts contributions <b>only while composing</b> (module
 * {@code bind} bodies and the deferred drain). When the build ends the stores
 * are sealed — a later {@code add}, or {@code before}/{@code after} on a
 * handle held past that point, fails instead of silently mutating or dropping.
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
public interface Contribution<T> {

    /**
     * Adds an unnamed contribution. Contributions are ordered by insertion
     * order and cannot use {@code before/after}. Returns {@code this} for
     * chaining additional contributions.
     *
     * @param value the contribution value
     * @return this Contribution, for chaining
     */
    Contribution<T> add(T value);

    /**
     * Adds a contribution by implementation class. The container instantiates
     * the class, injects dependencies, and invokes {@code @PostConstruct}.
     * An id is auto-generated as {@code snake_name@package} — the class simple
     *  name in camel-to-snake form, plus the package it lives in, because two
     *  packages may contribute the same simple name. {@code before/after}
     *  ordering matches on that full id (e.g. {@code .after("core_bean@com.acme")}).
     *
     * @param implClass the implementation class
     * @return an Ordering handle for declaring before/after constraints
     */
    Ordering add(Class<? extends T> implClass);

    /**
     * Adds a named contribution with ordering support. Duplicate ids are
     * rejected. Returns an {@link Ordering} handle for declaring
     * ordering constraints.
     *
     * @param id    unique id for ordering
     * @param value the contribution value
     * @return an Ordering handle for declaring before/after constraints
     * @throws IllegalStateException if the id is a duplicate
     */
    Ordering add(String id, T value);

    /**
     * Adds a named contribution built by a factory that receives the
     * {@link Container} — for entries whose construction needs services
     * bound by any module. Instantiated in the same deferred phase as
     * {@link #add(Class)}: after every module has bound, so declaration
     * order cannot matter. Duplicate ids are rejected.
     *
     * @param id      unique id for ordering
     * @param factory builds the contribution with the container
     * @return an Ordering handle for declaring before/after constraints
     * @throws IllegalStateException if the id is a duplicate
     */
    Ordering add(String id, Function<Container, ? extends T> factory);
}
