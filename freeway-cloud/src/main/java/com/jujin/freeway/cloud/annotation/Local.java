package com.jujin.freeway.cloud.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Backend marker: the local in-process default implementation of a role that an
 * extension module may replace. Core modules mark their defaults with
 * {@code .marker(Local.class)} (or a module-level {@code @Marker(Local.class)}),
 * and an adapter binds its alternative with {@code .primary()}; this marker is
 * what {@code isActiveBinding(type, Local.class)} and the backend-type guard ask
 * about.
 *
 * <pre>{@code
 *   @Inject @Local ServiceDiscovery local;   // the built-in, even when an adapter is primary
 * }</pre>
 *
 * <p>There is deliberately no {@code TYPE} position: nothing reads this
 * annotation off a class. A class-carried framework marker is read by ioc's
 * {@code MarkerIndex}, which cannot know a cloud annotation — so marking an
 * implementation class with {@code @Local} would compile and silently do
 * nothing. Mark the <em>binding</em> instead
 * ({@code binder.bind(X.class).to(Impl.class).marker(Local.class)}), and let
 * misuse as a class annotation be a compile error.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.FIELD})
public @interface Local {
}
