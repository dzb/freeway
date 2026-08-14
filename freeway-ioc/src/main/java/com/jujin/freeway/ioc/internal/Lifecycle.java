package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.commons.bean.MethodHandleUtils;
import com.jujin.freeway.ioc.annotation.PostConstruct;
import com.jujin.freeway.ioc.annotation.PreDestroy;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

final class Lifecycle {
    private static final ClassValue<LifecyclePlan> PLANS = new ClassValue<>() {
        @Override
        protected LifecyclePlan computeValue(Class<?> type) {
            return LifecyclePlan.of(type);
        }
    };

    private Lifecycle() {
    }

    static void invokePostConstruct(Object instance) {
        invokeLifecycle(PLANS.get(instance.getClass()).postConstruct(), instance, "@PostConstruct");
    }

    static void invokePreDestroy(Object instance) {
        invokeLifecycle(PLANS.get(instance.getClass()).preDestroy(), instance, "@PreDestroy");
    }

    private static void invokeLifecycle(MethodHandle handle, Object instance, String annotationName) {
        if (handle == null) {
            return;
        }
        try {
            MethodHandleUtils.invoke(handle, instance);
        } catch (Throwable ex) {
            // Errors included: a throwing callback is a failure of that
            // callback, not a reason to skip the rest of the lifecycle drain.
            // Callers (Shutdown, ScopedCache cleanup) handle the resulting
            // RuntimeException uniformly.
            throw new RuntimeException(
                annotationName + " invocation failed on " + instance.getClass().getName(), ex
            );
        }
    }

    private static Method findLifecycleMethod(Class<?> clazz, Class<? extends Annotation> annotationType) {
        Method result = null;
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            Method found = null;
            for (Method m : c.getDeclaredMethods()) {
                if (!m.isAnnotationPresent(annotationType)) {
                    continue;
                }
                if (m.getParameterCount() != 0 || m.getReturnType() != void.class
                    || Modifier.isStatic(m.getModifiers())) {
                    throw new IllegalArgumentException(
                        "@" + annotationType.getSimpleName() + " method must be non-static, take no parameters, and return void: "
                        + c.getName() + "." + m.getName()
                    );
                }
                if (found != null) {
                    throw new IllegalArgumentException(
                        "Multiple @" + annotationType.getSimpleName() + " methods found in class "
                        + c.getName()
                    );
                }
                found = m;
            }
            if (found != null) {
                if (result == null) {
                    result = found;
                } else if (result.getName().equals(found.getName())) {
                    // The subclass overrides the parent's method (both are
                    // zero-parameter void by the validation above): run the
                    // subclass's once, never the parent's — matching Java
                    // override semantics for the inherited method.
                    continue;
                } else {
                    // A parent and a child each declare a lifecycle method
                    // with a different name. Silently picking one would skip
                    // the other's cleanup/init — fail loudly instead and let
                    // the user merge them.
                    throw new IllegalArgumentException(
                        "Multiple @" + annotationType.getSimpleName()
                            + " methods found in the type hierarchy of " + clazz.getName()
                            + ": " + result.getDeclaringClass().getName() + "." + result.getName()
                            + " and " + c.getName() + "." + found.getName()
                            + " — keep one lifecycle method per hierarchy (merge or rename)"
                    );
                }
            }
        }
        return result;
    }

    private record LifecyclePlan(MethodHandle postConstruct, MethodHandle preDestroy) {
        private LifecyclePlan {
        }

        static LifecyclePlan of(Class<?> type) {
            Method postConstruct = findLifecycleMethod(type, PostConstruct.class);
            Method preDestroy = findLifecycleMethod(type, PreDestroy.class);
            return new LifecyclePlan(
                postConstruct == null ? null : MethodHandleUtils.methodHandle(postConstruct),
                preDestroy == null ? null : MethodHandleUtils.methodHandle(preDestroy)
            );
        }
    }
}
