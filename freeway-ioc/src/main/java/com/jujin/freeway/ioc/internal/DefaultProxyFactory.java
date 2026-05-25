package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.commons.bean.MethodHandleUtils;
import com.jujin.freeway.ioc.advisor.MethodAdvice;
import com.jujin.freeway.ioc.advisor.MethodInvocation;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicReference;

final class DefaultProxyFactory {
    @SuppressWarnings("unchecked")
    <T> T create(Class<T> interfaceType, Supplier<T> provider, String description) {
        Objects.requireNonNull(interfaceType, "interfaceType");
        Objects.requireNonNull(provider, "provider");
        if (!interfaceType.isInterface()) {
            throw new IllegalArgumentException(
                "ProxyFactory can only proxy interfaces: " + interfaceType.getName()
            );
        }
        InvocationHandler handler = new LazyHandler<>(provider, description);
        return (T) Proxy.newProxyInstance(
            interfaceType.getClassLoader(),
            new Class<?>[] { interfaceType },
            handler
        );
    }

    private static final class LazyHandler<T> implements InvocationHandler {
        private final Supplier<T> provider;
        private final String description;
        private final AtomicReference<Object> target = new AtomicReference<>();

        private LazyHandler(Supplier<T> provider, String description) {
            this.provider = provider;
            this.description = description;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return handleObjectMethod(proxy, method, args, description);
            }

            Object real = target.get();
            if (real == null) {
                real = provider.get();
                if (!target.compareAndSet(null, real)) {
                    real = target.get();
                }
            }
            MethodHandle handle = MethodHandleUtils.methodHandle(method);
            return MethodHandleUtils.invoke(handle, real, args);
        }
    }

    static Object handleObjectMethod(Object proxy, Method method, Object[] args, String toStringValue) {
        if (method.getDeclaringClass() != Object.class) {
            throw new IllegalArgumentException("Method is not declared on Object: " + method);
        }
        return switch (method.getName()) {
            case "toString" -> toStringValue != null ? toStringValue : proxy.getClass().getName();
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> args != null && args.length > 0 && proxy == args[0];
            default -> throw new UnsupportedOperationException("Unsupported Object method: " + method);
        };
    }

    @SuppressWarnings("unchecked")
    <T> T createAdvised(
        Class<T> interfaceType,
        Supplier<T> provider,
        String description,
        List<AdviceEntry> advices
    ) {
        Objects.requireNonNull(interfaceType, "interfaceType");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(advices, "advices");
        if (!interfaceType.isInterface()) {
            throw new IllegalArgumentException(
                "ProxyFactory can only proxy interfaces: " + interfaceType.getName()
            );
        }
        InvocationHandler handler = new AdvisedHandler<>(provider, description, advices);
        return (T) Proxy.newProxyInstance(
            interfaceType.getClassLoader(),
            new Class<?>[]{interfaceType},
            handler
        );
    }

    private static final class AdvisedHandler<T> implements InvocationHandler {
        private final Supplier<T> provider;
        private final String description;
        private final List<AdviceEntry> advices;
        private final AtomicReference<Object> target = new AtomicReference<>();

        private AdvisedHandler(Supplier<T> provider, String description, List<AdviceEntry> advices) {
            this.provider = provider;
            this.description = description;
            this.advices = advices;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return handleObjectMethod(proxy, method, args, description);
            }

            Object real = target.get();
            if (real == null) {
                real = provider.get();
                if (!target.compareAndSet(null, real)) {
                    real = target.get();
                }
            }
            return invokeAdvised(real, method, args, advices, 0);
        }

        private Object invokeAdvised(
            Object real,
            Method method,
            Object[] args,
            List<AdviceEntry> entries,
            int index
        ) throws Throwable {
            for (int i = index; i < entries.size(); i++) {
                AdviceEntry entry = entries.get(i);
                int nextIndex = i + 1;
                MethodInvocationContext context = new MethodInvocationContext(
                    real,
                    method,
                    args,
                    () -> invokeAdvised(real, method, args, entries, nextIndex)
                );
                if (entry.selector().test(context)) {
                    return entry.advice().invoke(context);
                }
            }
            MethodHandle handle = MethodHandleUtils.methodHandle(method);
            return MethodHandleUtils.invoke(handle, real, args);
        }
    }

    private record MethodInvocationContext(
        Object target,
        Method method,
        Object[] arguments,
        ProceedStep proceedStep
    ) implements MethodInvocation {
        private MethodInvocationContext {
            arguments = arguments == null ? new Object[0] : arguments.clone();
        }

        @Override
        public Object proceed() throws Throwable {
            return proceedStep.invoke();
        }

        @Override
        public Object[] arguments() {
            return arguments.clone();
        }
    }

    @FunctionalInterface
    private interface ProceedStep {
        Object invoke() throws Throwable;
    }
}
