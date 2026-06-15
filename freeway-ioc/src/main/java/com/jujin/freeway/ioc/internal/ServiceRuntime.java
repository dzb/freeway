package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.commons.scoped.ScopedCache;
import com.jujin.freeway.ioc.Scope;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class ServiceRuntime {
    private final ProxyFactory proxyFactory;
    private final Map<ServiceKey, Object> serviceCache;
    private final Map<ServiceKey, Object> targetCache;
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
    }

    <T> T get(BindingImpl<T> binding) {
        ServiceKey key = new ServiceKey(binding.type(), binding.id());
        if (binding.scope() == Scope.PROTOTYPE) {
            return binding.type().cast(binding.directInstance());
        }
        if (binding.scope() == Scope.THREAD && !binding.isProxiable()) {
            requireAdviceSupported(binding);
            return binding.type().cast(realize(binding));
        }
        return binding.type().cast(resolve(key, binding));
    }

    private <T> Object resolve(ServiceKey key, BindingImpl<T> binding) {
        Object cached = serviceCache.get(key);
        if (cached != null) {
            return cached;
        }
        Object service = create(binding);
        Object previous = serviceCache.putIfAbsent(key, service);
        return previous != null ? previous : service;
    }

    private <T> Object create(BindingImpl<T> binding) {
        if (binding.isProxiable()) {
            return binding.advices().isEmpty()
                ? proxyFactory.create(
                    binding.type(),
                    () -> realize(binding),
                    binding.type().getSimpleName() + "[" + binding.id() + "]"
                )
                : createAdvised(binding);
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
        Object cached = targetCache.get(key);
        if (cached != null) {
            return binding.type().cast(cached);
        }
        Set<ServiceKey> stack = realizeStack.get();
        if (!stack.add(key)) {
            throw new IllegalStateException("Circular dependency detected: " + key);
        }
        try {
            synchronized (targetCache) {
                cached = targetCache.get(key);
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
        return binding.type().cast(ScopedCache.get(key, binding::directInstance));
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

    @SuppressWarnings("unchecked")
    private <T> T createAdvised(BindingImpl<T> binding) {
        return proxyFactory.createAdvised(
            binding.type(),
            () -> realize(binding),
            binding.type().getSimpleName() + "[" + binding.id() + "]",
            binding.advices()
        );
    }
}
