package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.commons.bean.MethodHandleUtils;
import com.jujin.freeway.ioc.advisor.MethodInvocation;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The JDK proxy behind every interface binding: the lazy resolving proxy, the
 * advice chain, and the one-instance-per-get rule for prototype targets.
 */
final class ServiceProxy {
    private ServiceProxy() {
    }

    /**
     * The resolving proxy: the target runs on first invocation, and the
     * advices whose selector matches wrap the invocation. When
     * {@code cacheTarget} is set the handler resolves the target exactly once
     * per proxy and reuses it for every subsequent invocation — used for
     * PROTOTYPE targets so a proxy behaves like a single lazily-created
     * instance ("one instance per get(), state persists across calls") instead
     * of creating a fresh target per method call. Must NOT be set for
     * THREAD-scoped targets — their identity is per-scope, not per-proxy.
     */
    @SuppressWarnings("unchecked")
    static <T> T create(
        Class<T> interfaceType,
        Supplier<T> target,
        String description,
        List<AdviceEntry> advices,
        boolean cacheTarget
    ) {
        return (T) Proxy.newProxyInstance(
            interfaceType.getClassLoader(),
            new Class<?>[] { interfaceType },
            new AdvisedHandler<>(target, description, advices, cacheTarget)
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
                return switch (method.getName()) {
                    case "toString" -> description;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> args != null && args.length > 0 && proxy == args[0];
                    default -> throw new UnsupportedOperationException("Unsupported Object method: " + method);
                };
            }
            return invokeAdvised(resolveTarget(), method, args, 0);
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

        private Object invokeAdvised(Object real, Method method, Object[] args, int index)
            throws Throwable {
            for (int i = index; i < advices.size(); i++) {
                AdviceEntry entry = advices.get(i);
                int nextIndex = i + 1;
                MethodInvocationContext context = new MethodInvocationContext(
                    real,
                    method,
                    args,
                    () -> invokeAdvised(real, method, args, nextIndex)
                );
                if (entry.selector().test(context)) {
                    return entry.advice().invoke(context);
                }
            }
            MethodHandle handle = targetHandles.computeIfAbsent(
                method, MethodHandleUtils::methodHandle);
            return MethodHandleUtils.invokeOn(handle, real, args);
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
