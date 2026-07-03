package com.jujin.freeway.ioc.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation for framework-provided services. All services
 * defined by core framework modules (IoC, HTTP, DB) carry this
 * marker via module-level {@code &#64;Marker(Builtin.class)}.
 * <p>
 * Use at the injection point to ensure the framework version of a
 * service is resolved, even when a user module provides an override
 * of the same type:
 *
 * <pre>{@code
 *   &#64;Inject &#64;Builtin SymbolSource symbols;
 * }</pre>
 */
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Builtin {
}
