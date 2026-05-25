package com.jujin.freeway2.commons.bean;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

public interface BeanParameter {
    Type type();

    Annotation[] annotations();

    default <A extends Annotation> A annotation(Class<A> type) {
        for (Annotation annotation : annotations()) {
            if (type.isInstance(annotation)) {
                return type.cast(annotation);
            }
        }
        return null;
    }

    default boolean hasAnnotation(Class<? extends Annotation> type) {
        return annotation(type) != null;
    }
}
