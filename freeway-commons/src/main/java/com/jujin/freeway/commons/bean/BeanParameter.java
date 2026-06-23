package com.jujin.freeway.commons.bean;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

/**
 * Describes a single constructor parameter: its type and annotations.
 *
 * <p>Obtained from {@link BeanConstructor#parameters()}.
 */
public interface BeanParameter {

    /** Returns the parameter type. */
    Type type();

    /** Returns the annotations declared on this parameter. */
    Annotation[] annotations();

    /**
     * Looks up an annotation by type on this parameter.
     *
     * @param type the annotation type
     * @return the annotation, or null if not present
     */
    default <A extends Annotation> A annotation(Class<A> type) {
        for (Annotation annotation : annotations()) {
            if (type.isInstance(annotation)) {
                return type.cast(annotation);
            }
        }
        return null;
    }

    /** Returns true if this parameter has the given annotation. */
    default boolean hasAnnotation(Class<? extends Annotation> type) {
        return annotation(type) != null;
    }
}
