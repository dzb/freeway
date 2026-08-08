package com.jujin.freeway.ioc.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a service implementation is NOT thread-safe: concurrent
 * access corrupts state, so a singleton holder must not inject it.
 *
 * <p>A marker annotation — apply it directly on the implementation class
 * (like {@code @Primary}), or list it via {@link Marker}. Bindings created
 * with {@code binder.bind(X.class).to(Impl.class)} inherit the contract
 * automatically; the container rejects injection of a {@code @NotThreadSafe}
 * concrete class into a singleton holder (a thread-scoped or prototype
 * holder is fine — each thread/request gets its own instance). Resolve by
 * marker via {@code container.get(X.class, NotThreadSafe.class)}.
 *
 * <p>This is a declaration, not a proof. An implementation annotated with
 * both {@link ThreadSafe} and {@link NotThreadSafe} is rejected at binding
 * time. Unannotated services carry no contract and are not validated.
 *
 * @see ThreadSafe
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface NotThreadSafe {
}
