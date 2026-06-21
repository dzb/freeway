package com.jujin.freeway.commons.bean;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Wraps a {@link Constructor} with a cached {@link MethodHandle} for fast
 * invocation and provides access to constructor parameters and annotations.
 *
 * <p>Obtained via {@link BeanIntrospector#constructor(Constructor)} or
 * {@link BeanIntrospector#selectConstructor(Class, Class)}.
 */
public final class BeanConstructor {
    private final Constructor<?> constructor;
    private final MethodHandle handle;
    private final Annotation[] annotations;
    private final List<BeanParameter> parameters;

    private BeanConstructor(Constructor<?> constructor, MethodHandle handle, Annotation[] annotations, List<BeanParameter> parameters) {
        this.constructor = constructor;
        this.handle = handle;
        this.annotations = annotations.clone();
        this.parameters = List.copyOf(parameters);
    }

    /**
     * Wraps a JDK {@link Constructor} in a cached {@link BeanConstructor}.
     *
     * @param constructor the constructor to wrap
     * @return a new bean constructor with a cached method handle
     */
    public static BeanConstructor of(Constructor<?> constructor) {
        Objects.requireNonNull(constructor, "constructor");
        return new BeanConstructor(
            constructor,
            MethodHandleUtils.constructorHandle(constructor),
            constructor.getAnnotations(),
            parameters(constructor)
        );
    }

    /**
     * Returns the underlying JDK constructor.
     *
     * @return the JDK constructor
     */
    public Constructor<?> constructor() {
        return constructor;
    }

    /** Returns the annotations declared on this constructor. */
    public Annotation[] annotations() {
        return annotations.clone();
    }

    /** Returns the constructor parameter descriptors. */
    public List<BeanParameter> parameters() {
        return parameters;
    }

    /**
     * Looks up an annotation by type on this constructor.
     *
     * @param type the annotation class to look for
     * @param <A>  the annotation type
     * @return the annotation, or null if not present
     */
    public <A extends Annotation> A annotation(Class<A> type) {
        for (Annotation annotation : annotations) {
            if (type.isInstance(annotation)) {
                return type.cast(annotation);
            }
        }
        return null;
    }

    /**
     * Returns true if this constructor has the given annotation.
     *
     * @param type the annotation class to check for
     * @return true if the annotation is present
     */
    public boolean hasAnnotation(Class<? extends Annotation> type) {
        return annotation(type) != null;
    }

    /**
     * Invokes the constructor with the given arguments.
     *
     * @param args the constructor arguments
     * @return a new instance
     * @throws IllegalArgumentException if the constructor throws
     */
    public Object newInstance(Object... args) {
        try {
            return handle.invokeWithArguments(args == null ? new Object[0] : args);
        } catch (Error e) { throw e; } catch (Throwable ex) {
            throw new IllegalArgumentException("Cannot invoke constructor: " + constructor, ex);
        }
    }

    private static List<BeanParameter> parameters(Constructor<?> constructor) {
        Parameter[] rawParameters = constructor.getParameters();
        List<BeanParameter> parameters = new ArrayList<>(rawParameters.length);
        for (Parameter parameter : rawParameters) {
            parameters.add(new BeanParameterDefault(parameter));
        }
        return parameters;
    }

    private record BeanParameterDefault(Type type, Annotation[] annotations) implements BeanParameter {
        private BeanParameterDefault(Parameter parameter) {
            this(parameter.getParameterizedType(), parameter.getAnnotations());
        }

        @Override
        public Annotation[] annotations() {
            return annotations.clone();
        }
    }
}
