package com.jujin.freeway.ioc;

/**
 * Service lookup container. Created by {@link Freeway#create(Module2...)}.
 *
 * <p>Services are bound via {@link Binder#bind(Class)} in modules and resolved
 * by type (and optionally by id) at runtime.
 *
 * <p>Usage:
 * <pre>{@code
 * Container c = Freeway.create(binder -> {
 *     binder.bind(Greeter.class).to(GreeterImpl.class);
 * });
 * Greeter g = c.get(Greeter.class);
 * c.close();
 * }</pre>
 */
import com.jujin.freeway.ioc.extension.Extension;

public interface Container extends AutoCloseable {

    /**
     * Returns a service by type. Requires a unique or primary binding.
     *
     * @param type the service type
     * @param <T>  the service type
     * @return the service instance
     * @throws IllegalArgumentException if no binding, multiple bindings
     *         without a primary, or multiple primaries exist
     */
    <T> T get(Class<T> type);

    /**
     * Returns a named service by type and id.
     *
     * @param type the service type
     * @param id   the binding id
     * @param <T>  the service type
     * @return the service instance
     * @throws IllegalArgumentException if no binding matches
     */
    <T> T get(Class<T> type, String id);

    /**
     * Returns the extension point for the given entry type, providing access
     * to all contributed values of that type across all modules.
     *
     * @param entryType the extension point type (e.g. {@code Route.class})
     * @param <T>       the entry type
     * @return the extension handle
     */
    <T> Extension<T> extension(Class<T> entryType);

    @Override
    void close();
}
