package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.advisor.Advisor;
import java.lang.annotation.Annotation;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Fluent binding DSL returned by {@link Binder#bind(Class)}.
 *
 * <p>Example:
 * <pre>{@code
 * binder.bind(PaymentGateway.class)
 *     .to(StripeGateway.class)
 *     .id("stripe")
 *     .primary()
 *     .scope(Scope.SINGLETON);
 * }</pre>
 *
 * @param <T> the service type
 */
public interface Binding<T> {

    /**
     * Binds to an implementation class. The container will instantiate it,
     * resolving its constructor via injection.
     */
    Binding<T> to(Class<? extends T> implementation);

    /**
     * Binds to a provider function that creates the service on demand.
     * The function receives the {@link Container} for dependency resolution.
     */
    Binding<T> to(Function<Container, ? extends T> provider);

    /**
     * Sets the service scope. Default is {@link Scope#SINGLETON}.
     */
    Binding<T> scope(Scope scope);

    /**
     * Assigns a string id for named resolution.
     */
    Binding<T> id(String id);

    /**
     * Marks this binding as primary. When multiple bindings exist for the
     * same type, the primary one is resolved when no id is specified.
     *
     * @throws IllegalArgumentException if multiple primary bindings exist
     */
    Binding<T> primary();

    /**
     * Attaches marker annotations to this binding. Markers enable
     * type-safe service disambiguation at injection points.
     *
     * <pre>{@code
     *   binder.bind(Cache.class).to(FastCache.class)
     *       .marker(Fast.class);
     *
     *   // At injection point:
     *   &#64;Inject &#64;Fast Cache cache;
     * }</pre>
     *
     * @param markers marker annotation classes (must have &#64;Retention(RUNTIME))
     */
    Binding<T> marker(Class<? extends Annotation>... markers);

    /**
     * Registers AOP advice for this service. Only works for interface-to-class
     * bindings (JDK dynamic proxy).
     */
    Binding<T> advise(Consumer<Advisor> advisor);
}
