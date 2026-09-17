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
 * automatically; the container rejects injecting a {@code @NotThreadSafe}
 * implementation into a singleton holder, behind the concrete class or
 * behind its interface (a singleton proxy caches exactly one target, so
 * proxying does not launder the marker). Holders that are safe: prototype
 * and thread-scoped (each resolution gets its own instance), and a
 * THREAD-scoped interface binding (its proxy resolves per scope). Resolve
 * by marker via {@code container.get(X.class, NotThreadSafe.class)}.
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
