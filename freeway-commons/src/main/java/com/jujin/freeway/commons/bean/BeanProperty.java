package com.jujin.freeway.commons.bean;
import java.lang.reflect.ParameterizedType;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Optional;

/**
 * Describes a single property of a bean or record: its name, type,
 * annotations, and read/write access.
 */
public interface BeanProperty {

    /** Returns the property name. */
    String name();

    /** Returns the property type (may be a {@link ParameterizedType}). */
    Type type();

    /** Returns the annotations declared on this property. */
    Annotation[] annotations();

    /** Returns true if this property is writable (has a setter or is an immediate field). */
    boolean isWritable();

    /**
     * Returns true if this property is backed by an instance field (a plain
     * field or a record component), as opposed to a getter-only computed
     * value. Lets diagnostics distinguish a final field from a derived
     * getter property — both are non-writable, but the remedies differ.
     */
    default boolean isFieldBacked() {
        return false;
    }

    /** Reads the property value from the given target instance. */
    Object read(Object target);

    /** Writes a value to this property on the given target instance. */
    void write(Object target, Object value);

    /**
     * Looks up an annotation by type on this property.
     *
     * @param type the annotation type
     * @return the annotation, or {@link Optional#empty()} if not present
     */
    default <A extends Annotation> Optional<A> annotation(Class<A> type) {
        for (Annotation annotation : annotations()) {
            if (type.isInstance(annotation)) {
                return Optional.of(type.cast(annotation));
            }
        }
        return Optional.empty();
    }

    /** Returns true if this property has the given annotation. */
    default boolean hasAnnotation(Class<? extends Annotation> type) {
        return annotation(type).isPresent();
    }
}
