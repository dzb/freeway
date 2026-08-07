package com.jujin.freeway.ioc;

/**
 * Service lookup container. Created by {@link Freeway#create(ModuleEx...)}.
 *
 * <p>Services are bound via {@link Binder#bind(Class)} in modules and resolved
 * by type (and optionally by id) at runtime.
 *
 * <p>Container is a framework boundary type — it is not injectable via
 * {@code @Inject}. Access it through:
 * <ul>
 *   <li>{@link Freeway#create(ModuleEx...)} — test and standalone usage</li>
 *   <li>{@link com.jujin.freeway.ioc.RuntimeHook#start(Container)} — lifecycle callbacks</li>
 *   <li>Provider lambdas via {@code binder.bind(X.class).to(container -> ...)}</li>
 * </ul>
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
import java.lang.annotation.Annotation;

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
     * Returns a service by type and marker annotations. The binding must
     * carry all the specified marker annotations (containsAll semantics).
     *
     * <pre>{@code
     *   // Define a marker annotation
     *   &#64;Retention(RUNTIME) &#64;Target({TYPE, PARAMETER, FIELD})
     *   public &#64;interface Fast {}
     *
     *   // Bind with marker
     *   binder.bind(Cache.class).to(FastCache.class).marker(Fast.class);
     *
     *   // Resolve by marker
     *   Cache cache = container.get(Cache.class, Fast.class);
     * }</pre>
     *
     * @param type    the service type
     * @param markers marker annotation classes to match
     * @param <T>     the service type
     * @return the service instance
     * @throws IllegalArgumentException if no binding or multiple bindings match
     */
    <T> T get(Class<T> type, Class<? extends Annotation>... markers);

    /**
     * Returns the extension point for the given entry type, providing access
     * to all contributed values of that type across all modules.
     *
     * @param entryType the extension point type (e.g. {@code Route.class})
     * @param <T>       the entry type
     * @return the extension handle
     */
    <T> Extension<T> extension(Class<T> entryType);

    /**
     * Creates a fully-injected instance of the given type without registering it
     * in the container. The instance receives constructor injection, field
     * injection, and {@code @PostConstruct} — but is not cached, not managed,
     * and will not be returned by future {@code get()} calls.
     *
     * @param type the implementation class to create
     * @param <T>  the instance type
     * @return a new, injected instance (caller owns the lifecycle)
     */
    <T> T create(Class<T> type);

    /**
     * Closes the container: runs {@code @PreDestroy} and {@code AutoCloseable}
     * on every realized singleton, then clears the caches. Idempotent —
     * repeated calls are no-ops.
     *
     * <p>{@code @PreDestroy} callbacks run BEFORE the container is sealed,
     * so cleanup code may still resolve services via {@link #get(Class)} /
     * {@link #extension(Class)}.</p>
     *
     * <p>Only <em>realized</em> singletons are cleaned up. A lazy proxy for an
     * interface binding that was never invoked is not instantiated at close,
     * so its target never receives {@code @PreDestroy} — instantiate the
     * service (or call a proxy method) if it must be destroyed explicitly.</p>
     */
    @Override
    void close();
}
