package com.jujin.freeway.ioc.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a service implementation is thread-safe and may be shared
 * freely: a singleton holder may inject it, concurrent access is safe.
 *
 * <p>A marker annotation — apply it directly on the implementation class
 * (like {@code @Primary}), or list it via {@link Marker}. Bindings created
 * with {@code binder.bind(X.class).to(Impl.class)} inherit the contract
 * automatically; resolve by marker via
 * {@code container.get(X.class, ThreadSafe.class)}.
 *
 * <p>This is a declaration, not a proof — marking an unsafe implementation
 * as thread-safe is the author's error. An implementation annotated with
 * both {@link ThreadSafe} and {@link NotThreadSafe} is rejected at binding
 * time. Unannotated services carry no contract and are not validated.
 *
 * @see NotThreadSafe
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ThreadSafe {
}
