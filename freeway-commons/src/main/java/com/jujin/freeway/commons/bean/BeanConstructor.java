package com.jujin.freeway.commons.bean;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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

    public static BeanConstructor of(Constructor<?> constructor) {
        Objects.requireNonNull(constructor, "constructor");
        return new BeanConstructor(
            constructor,
            MethodHandleUtils.constructorHandle(constructor),
            constructor.getAnnotations(),
            parameters(constructor)
        );
    }

    public Constructor<?> constructor() {
        return constructor;
    }

    public Annotation[] annotations() {
        return annotations.clone();
    }

    public List<BeanParameter> parameters() {
        return parameters;
    }

    public <A extends Annotation> A annotation(Class<A> type) {
        for (Annotation annotation : annotations) {
            if (type.isInstance(annotation)) {
                return type.cast(annotation);
            }
        }
        return null;
    }

    public boolean hasAnnotation(Class<? extends Annotation> type) {
        return annotation(type) != null;
    }

    public Object newInstance(Object... args) {
        try {
            return handle.invokeWithArguments(args == null ? new Object[0] : args);
        } catch (Throwable ex) {
            throw new IllegalArgumentException("Cannot invoke constructor: " + constructor, ex);
        }
    }

    private static List<BeanParameter> parameters(Constructor<?> constructor) {
        Parameter[] rawParameters = constructor.getParameters();
        List<BeanParameter> parameters = new ArrayList<>(rawParameters.length);
        for (Parameter parameter : rawParameters) {
            parameters.add(new DefaultBeanParameter(parameter));
        }
        return parameters;
    }

    private record DefaultBeanParameter(Type type, Annotation[] annotations) implements BeanParameter {
        private DefaultBeanParameter(Parameter parameter) {
            this(parameter.getParameterizedType(), parameter.getAnnotations());
        }

        @Override
        public Annotation[] annotations() {
            return annotations.clone();
        }
    }
}
