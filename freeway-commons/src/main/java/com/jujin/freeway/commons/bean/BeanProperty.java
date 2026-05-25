package com.jujin.freeway2.commons.bean;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

public interface BeanProperty {
    String name();

    Type type();

    Annotation[] annotations();

    boolean writable();

    Object read(Object target);

    void write(Object target, Object value);

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
