package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.commons.bean.MethodHandleUtils;
import com.jujin.freeway.ioc.advisor.MethodInvocation;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

final class ProxyFactoryDefault implements ProxyFactory {
    @SuppressWarnings("unchecked")
    @Override
    public <T> T create(Class<T> interfaceType, Supplier<T> provider, String description) {
        requireInterface(interfaceType);
        Objects.requireNonNull(provider, "provider");
        return newProxy(interfaceType, new LazyHandler<>(provider, description));
    }

    private static final class LazyHandler<T> implements InvocationHandler {
        private final Supplier<T> provider;
        private final String description;

        private LazyHandler(Supplier<T> provider, String description) {
            this.provider = provider;
            this.description = description;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return handleObjectMethod(proxy, method, args, description);
            }

            Object real = provider.get();
            return invokeTarget(real, method, args);
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
    @Override
    public <T> T createAdvised(
        Class<T> interfaceType,
        Supplier<T> provider,
        String description,
        List<AdviceEntry> advices
    ) {
        requireInterface(interfaceType);
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(advices, "advices");
        return newProxy(interfaceType, new AdvisedHandler<>(provider, description, advices));
    }

    private static final class AdvisedHandler<T> implements InvocationHandler {
        private final Supplier<T> provider;
        private final String description;
        private final List<AdviceEntry> advices;

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

            Object real = provider.get();
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
            return invokeTarget(real, method, args);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T newProxy(Class<T> interfaceType, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(
            interfaceType.getClassLoader(),
            new Class<?>[] { interfaceType },
            handler
        );
    }

    private static Object invokeTarget(Object real, Method method, Object[] args) throws Throwable {
        MethodHandle handle = MethodHandleUtils.methodHandle(method);
        return MethodHandleUtils.invoke(handle, real, args);
    }

    private static void requireInterface(Class<?> interfaceType) {
        Objects.requireNonNull(interfaceType, "interfaceType");
        if (!interfaceType.isInterface()) {
            throw new IllegalArgumentException(
                "ProxyFactory can only proxy interfaces: " + interfaceType.getName()
            );
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
