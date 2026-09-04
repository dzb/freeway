package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.commons.bean.MethodHandleUtils;
import com.jujin.freeway.ioc.advisor.MethodInvocation;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

final class ProxyFactoryImpl implements ProxyFactory {
    @Override
    public <T> T create(Class<T> interfaceType, Supplier<T> provider, String description) {
        return createAdvised(interfaceType, provider, description, List.of(), false);
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
        List<AdviceEntry> advices,
        boolean cacheTarget
    ) {
        requireInterface(interfaceType);
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(advices, "advices");
        return newProxy(
            interfaceType,
            new AdvisedHandler<>(provider, description, advices, cacheTarget)
        );
    }

    private static final class AdvisedHandler<T> implements InvocationHandler {
        private final Supplier<T> provider;
        private final String description;
        private final List<AdviceEntry> advices;
        private final boolean cacheTarget;
        private volatile T cachedTarget;
        /** Target handles for this proxy's interface methods — keeps the
         *  invocation hot path free of even a shared-map lookup. Bounded by
         *  the interface method count. */
        private final ConcurrentHashMap<Method, MethodHandle> targetHandles = new ConcurrentHashMap<>();

        private AdvisedHandler(
            Supplier<T> provider,
            String description,
            List<AdviceEntry> advices,
            boolean cacheTarget
        ) {
            this.provider = provider;
            this.description = description;
            this.advices = advices;
            this.cacheTarget = cacheTarget;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return handleObjectMethod(proxy, method, args, description);
            }

            return invokeAdvised(resolveTarget(), method, args, advices, 0);
        }

        /**
         * Per-proxy lazy target resolution. With {@link #cacheTarget} set the
         * provider runs once (first method call) and the result is reused for
         * every later call on THIS proxy — giving an advised PROTOTYPE the same
         * "one instance per get(), state persists across calls" semantics as an
         * unadvised prototype. A throwing provider is never cached, so a failed
         * construction is retried on the next call. Double-checked locking keeps
         * concurrent first calls from creating two targets.
         */
        private Object resolveTarget() {
            if (!cacheTarget) {
                return provider.get();
            }
            T target = cachedTarget;
            if (target != null) {
                return target;
            }
            synchronized (this) {
                target = cachedTarget;
                if (target == null) {
                    target = provider.get();
                    cachedTarget = target;
                }
                return target;
            }
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

        private Object invokeTarget(Object real, Method method, Object[] args) throws Throwable {
            MethodHandle handle = targetHandles.computeIfAbsent(
                method, MethodHandleUtils::methodHandle);
            return MethodHandleUtils.invokeOn(handle, real, args);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T newProxy(Class<T> interfaceType, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(
            interfaceType.getClassLoader(),
            new Class<?>[] { interfaceType },
            handler
        );
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
