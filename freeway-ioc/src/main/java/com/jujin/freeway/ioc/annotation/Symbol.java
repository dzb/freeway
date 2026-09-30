package com.jujin.freeway.ioc.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects a configured value, resolved from the key this names and coerced to
 * the target type.
 *
 * <p>The target omits {@link ElementType#CONSTRUCTOR} yet a constructor
 * parameter can carry it: a parameter — of a constructor or of any method — is
 * a {@link ElementType#PARAMETER}, and that is the element the target governs.
 *
 * <p><b>Not a method parameter.</b> The container reads this annotation on
 * constructor parameters and fields only. On a method parameter it compiles,
 * resolves nothing, and makes the container refuse to inject the declaring
 * class (at its first realization, scanning the type, its superclasses and
 * every interface it implements) — see
 * {@link com.jujin.freeway.ioc.annotation.Inject} for why the target cannot be
 * narrowed instead.
 */
@Target({ ElementType.PARAMETER, ElementType.FIELD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Symbol {
    String value();
}
