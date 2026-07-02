package com.jujin.freeway.ioc.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares one or more marker annotations for a service, enabling
 * injection based on the combination of type and marker.
 * <p>
 * Marker annotations should be empty — the mere presence of the
 * annotation is all that is needed. Markers are additive: a service
 * may accumulate markers from module-level defaults, builder method
 * annotations, and explicit {@code .marker()} declarations.
 * <p>
 * When applied to a module class (one that implements
 * {@link com.jujin.freeway.ioc.ModuleEx}), all services defined by
 * that module inherit the listed markers automatically.
 *
 * <pre>{@code
 *   // Define a marker annotation
 *   &#64;Retention(RUNTIME) &#64;Target({TYPE, PARAMETER, FIELD})
 *   public &#64;interface Fast {}
 *
 *   // Apply to a service
 *   &#64;Fast
 *   public class FastCache implements Cache {}
 *
 *   // Inject by marker
 *   &#64;Inject &#64;Fast Cache cache;
 * }</pre>
 *
 * @see com.jujin.freeway.ioc.Container#get(Class, Class[])
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Marker {
    /**
     * The marker annotation classes to associate with the service or module.
     */
    Class<?>[] value();
}
