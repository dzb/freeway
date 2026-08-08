package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.commons.scoped.ScopedCache;
import com.jujin.freeway.ioc.Scope;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Manages service lifetime: caching, scope enforcement, proxy wrapping,
 * and advice application. Uses lock striping (64 stripes) to reduce
 * contention while preventing duplicate realization of the same binding.
 */
final class ServiceRuntime {
    /**
     * Serializes first-time realization of singleton targets. A single lock
     * (not per-key stripes) prevents cross-stripe deadlock when two singletons
     * whose constructors depend on each other are first resolved concurrently
     * (A holds stripe X and waits for Y while B holds stripe Y and waits for X).
     * {@code synchronized} is reentrant, so recursive realization on the same
     * thread is safe, and the ThreadLocal realizeStack catches genuine cycles.
     * The lock only guards first-time construction — cached lookups are lock-free.
     *
     * <p>Package-visible so nothing outside this class needs it; the closed
     * re-check inside {@link #realize} runs under this lock so a realization
     * racing {@code close()} cannot orphan a fresh singleton after the caches
     * are cleared. The lock is deliberately NOT held across the container
     * drain — user lifecycle callbacks may join threads that realize
     * services, which would deadlock shutdown.
     */
    static final Object REALIZE_LOCK = new Object();

    private final ProxyFactory proxyFactory;
    private final Map<ServiceKey, Object> serviceCache;
    private final Map<ServiceKey, Object> targetCache;
    private final ThreadLocal<Set<ServiceKey>> realizeStack =
        ThreadLocal.withInitial(HashSet::new);
    private final ContainerImpl container;

    ServiceRuntime(
        ContainerImpl container,
        ProxyFactory proxyFactory,
        Map<ServiceKey, Object> serviceCache,
        Map<ServiceKey, Object> targetCache
    ) {
        this.container = container;
        this.proxyFactory = proxyFactory;
        this.serviceCache = serviceCache;
        this.targetCache = targetCache;
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
            synchronized (REALIZE_LOCK) {
                // A get() that passed the closed check before close() may
                // block here while close() drains; once the container is
                // sealed, realizing would write a fresh singleton into the
                // already-cleared caches and orphan it.
                if (container.isClosed()) {
                    throw new IllegalStateException("Container is closed");
                }
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
            return binding.type().cast(ScopedCache.get(key, () -> {
                Object created = binding.directInstance();
                ContainerImpl.manageScopeValue(container, created);
                return created;
            }));
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
        // PROTOTYPE targets must not route through realize(): that path caches
        // in targetCache, which would share one target across every proxy and
        // pull the prototype into container-close lifecycle. Per-call
        // directInstance() preserves instance independence.
        Supplier<T> target = binding.scope() == Scope.PROTOTYPE
            ? binding::directInstance
            : () -> realize(binding);
        return proxyFactory.createAdvised(
            binding.type(),
            target,
            binding.type().getSimpleName() + "[" + binding.id() + "]",
            binding.advices()
        );
    }
}
