package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.commons.scoped.ScopedCache;
import com.jujin.freeway.ioc.Scope;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Manages service lifetime: caching, scope enforcement, proxy wrapping,
 * and advice application. Uses lock striping (64 stripes) to reduce
 * contention while preventing duplicate realization of the same binding.
 */
final class ServiceRuntime {
    private static final int STRIPE_BITS = 6; // 64 stripes
    private static final int STRIPE_MASK = (1 << STRIPE_BITS) - 1;

    private final ProxyFactory proxyFactory;
    private final Map<ServiceKey, Object> serviceCache;
    private final Map<ServiceKey, Object> targetCache;
    private final Object[] lockStripes = new Object[1 << STRIPE_BITS];
    private final ThreadLocal<Set<ServiceKey>> realizeStack =
        ThreadLocal.withInitial(HashSet::new);

    ServiceRuntime(
        ProxyFactory proxyFactory,
        Map<ServiceKey, Object> serviceCache,
        Map<ServiceKey, Object> targetCache
    ) {
        this.proxyFactory = proxyFactory;
        this.serviceCache = serviceCache;
        this.targetCache = targetCache;
        for (int i = 0; i < lockStripes.length; i++) lockStripes[i] = new Object();
    }

    <T> T get(BindingImpl<T> binding) {
        if (binding.scope() == Scope.PROTOTYPE) {
            return binding.advices().isEmpty()
                ? binding.directInstance()
                : (T) createAdvised(binding);
        }
        if (binding.scope() == Scope.THREAD && !binding.isProxiable()) {
            requireAdviceSupported(binding);
            return realize(binding);
        }
        ServiceKey key = new ServiceKey(binding.type(), binding.id());
        return resolve(key, binding);
    }

    @SuppressWarnings("unchecked")
    private <T> T resolve(ServiceKey key, BindingImpl<T> binding) {
        Object cached = serviceCache.get(key);
        if (cached != null) {
            return (T) cached;
        }
        Object service = create(binding);
        Object previous = serviceCache.putIfAbsent(key, service);
        return (T) (previous != null ? previous : service);
    }

    @SuppressWarnings("unchecked")
    private <T> T create(BindingImpl<T> binding) {
        if (binding.isProxiable()) {
            return binding.advices().isEmpty()
                ? (T) proxyFactory.create(
                    binding.type(),
                    () -> realize(binding),
                    binding.type().getSimpleName() + "[" + binding.id() + "]"
                )
                : (T) createAdvised(binding);
        }
        requireAdviceSupported(binding);
        return realize(binding);
    }

    @SuppressWarnings("unchecked")
    <T> T realize(BindingImpl<T> binding) {
        if (binding.scope() == Scope.THREAD) {
            return realizeThreadScoped(binding);
        }
        ServiceKey key = new ServiceKey(binding.type(), binding.id());
        Set<ServiceKey> stack = realizeStack.get();
        if (!stack.add(key)) {
            throw new IllegalStateException("Circular dependency detected: " + key);
        }
        try {
            Object lock = lockStripes[key.hashCode() & STRIPE_MASK];
            synchronized (lock) {
                Object cached = targetCache.get(key);
                if (cached == null) {
                    cached = binding.directInstance();
                    targetCache.put(key, cached);
                }
                return binding.type().cast(cached);
            }
        } finally {
            stack.remove(key);
        }
    }

    private <T> T realizeThreadScoped(BindingImpl<T> binding) {
        if (!ScopedCache.isActive()) {
            throw new IllegalStateException(
                "No open scope for type " + binding.type().getName()
            );
        }
        ServiceKey key = new ServiceKey(binding.type(), binding.id());
        Set<ServiceKey> stack = realizeStack.get();
        if (!stack.add(key)) {
            throw new IllegalStateException("Circular dependency detected: " + key);
        }
        try {
            return binding.type().cast(ScopedCache.get(key, binding::directInstance));
        } finally {
            stack.remove(key);
        }
    }

    private static void requireAdviceSupported(BindingImpl<?> binding) {
        if (binding.advices().isEmpty()) {
            return;
        }
        throw new IllegalArgumentException(
            "Advisor is not supported on non-interface type " + binding.type().getName() +
            ". To use advice, bind " + binding.type().getName() + " to an interface."
        );
    }

    private <T> T createAdvised(BindingImpl<T> binding) {
        return proxyFactory.createAdvised(
            binding.type(),
            () -> realize(binding),
            binding.type().getSimpleName() + "[" + binding.id() + "]",
            binding.advices()
        );
    }
}
