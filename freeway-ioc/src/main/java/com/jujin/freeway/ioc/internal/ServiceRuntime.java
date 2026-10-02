package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.commons.scoped.ScopedCache;
import com.jujin.freeway.ioc.Scope;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages service lifetime for one container: the proxy and target caches,
 * scope enforcement, and proxy wrapping. First-time realization is serialized
 * by this container's {@link #realizeLock}.
 */
final class ServiceRuntime {
    private static final Logger LOG = LoggerFactory.getLogger(ServiceRuntime.class);
    /**
     * Serializes first-time realization of this container's singleton targets.
     * A single lock (not per-key stripes) prevents cross-stripe deadlock when
     * two singletons whose constructors depend on each other are first resolved
     * concurrently (A holds stripe X and waits for Y while B holds stripe Y and
     * waits for X). {@code synchronized} is reentrant, so recursive realization
     * on the same thread is safe, and the ThreadLocal realizeStack catches
     * genuine cycles. The lock only guards first-time construction — cached
     * lookups are lock-free.
     *
     * <p>Per container, not JVM-wide: a slow constructor in one container must
     * not hold up first-time realization in another, and a provider that waits
     * on work realizing from a second container must not deadlock on a lock the
     * two share only by accident.
     *
     * <p>The closed re-check inside {@link #realize} runs under this lock, and
     * {@link #seal} takes it, so a realization racing {@code close()} cannot
     * orphan a fresh singleton after the caches are cleared. The lock is
     * deliberately NOT held across the container's lifecycle drain — user
     * callbacks may join threads that realize services, which would deadlock
     * shutdown.
     */
    private final Object realizeLock = new Object();

    /** The lazy proxy served for each interface binding (SINGLETON/THREAD). */
    private final Map<ServiceKey, Object> proxyCache = new ConcurrentHashMap<>();
    /** Realized singleton targets — what shutdown runs lifecycle on. */
    private final Map<ServiceKey, Object> targetCache = new ConcurrentHashMap<>();
    /** Insertion-ordered so a cycle failure can print the realization path. */
    private final ThreadLocal<LinkedHashSet<ServiceKey>> realizeStack =
        ThreadLocal.withInitial(LinkedHashSet::new);
    private final ContainerImpl container;

    ServiceRuntime(ContainerImpl container) {
        this.container = container;
    }

    /** The live target cache — {@link Shutdown} drains lifecycle over it. */
    Map<ServiceKey, Object> targets() {
        return targetCache;
    }

    <T> T get(BindingImpl<T> binding) {
        if (binding.scope() == Scope.PROTOTYPE) {
            // No cache to pollute, but a PROTOTYPE instance built after the
            // container sealed would be fully constructed and never receive
            // @PreDestroy — a resource opened in its constructor with nothing
            // to close it. Same closed check as the realize path.
            if (container.isClosed()) {
                throw new IllegalStateException("Container is closed");
            }
            return binding.isAdvised() ? proxy(binding) : binding.directInstance();
        }
        if (!binding.isProxiable()) {
            // A concrete class: its singleton target (or the open thread
            // scope's value) is itself the service.
            return realize(binding);
        }
        ServiceKey key = new ServiceKey(binding.type(), binding.id());
        // A cache hit needs no lock: the proxy behind it is fully built before
        // the publish, so reading it unlocked is safe. Whether the container is
        // still open is a separate question this path deliberately does not ask
        // — a proxy obtained before close() and invoked after it throws from
        // the invocation, which is the contract every scope shares. A miss mints,
        // and the mint must hold the lock: that is what makes "still open?" and
        // "publish into proxyCache" one step, so a close() landing between them
        // cannot clear the cache and leave this thread repopulating a sealed one.
        Object cached = proxyCache.get(key);
        if (cached != null) {
            return binding.type().cast(cached);
        }
        synchronized (realizeLock) {
            if (container.isClosed()) {
                throw new IllegalStateException("Container is closed");
            }
            Object existing = proxyCache.get(key);
            if (existing != null) {
                return binding.type().cast(existing);
            }
            Object proxy = proxy(binding);
            proxyCache.put(key, proxy);
            return binding.type().cast(proxy);
        }
    }

    private <T> T realize(BindingImpl<T> binding) {
        if (binding.scope() == Scope.THREAD) {
            return realizeThreadScoped(binding);
        }
        ServiceKey key = new ServiceKey(binding.type(), binding.id());
        // Fast path: an interface proxy re-enters realize() on EVERY method
        // call, so a published singleton must be read without the lock. The
        // target is fully constructed before its CHM publish, so a cached
        // read is safe; realizeLock serializes only first-time construction
        // and the close-seal race below.
        Object ready = targetCache.get(key);
        if (ready != null) {
            return binding.type().cast(ready);
        }
        return withCycleGuard(key, () -> {
            synchronized (realizeLock) {
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
        });
    }

    private <T> T realizeThreadScoped(BindingImpl<T> binding) {
        // Kept even though get() now checks on the way in: a THREAD-scope proxy
        // outlives the get() that produced it, and its target is realized on
        // the first METHOD CALL, not at get() time. A holder invoking that
        // proxy after close() never passes through get() again, so this is the
        // only place that can refuse it.
        if (container.isClosed()) {
            throw new IllegalStateException("Container is closed");
        }
        if (!ScopedCache.isActive()) {
            throw new IllegalStateException(
                "No open scope for type " + binding.type().getName()
                    + " — wrap the call in"
                    + " container.get(Scoping.class).within(() -> ...)"
            );
        }
        ServiceKey key = new ServiceKey(binding.type(), binding.id());
        // The scope cache is process-wide: qualify the key with this runtime,
        // or two containers binding the same type and id would share one value
        // inside a scope.
        ScopeKey scoped = new ScopeKey(this, key);
        // Lifecycle travels with the cache entry: scope exit runs it even when
        // the container closed first, and no process-wide side set is needed.
        return withCycleGuard(key, () -> binding.type().cast(ScopedCache.get(scoped,
            binding::directInstance, v -> {
            Lifecycle.invokePreDestroy(v);
            if (v instanceof AutoCloseable c) {
                try {
                    c.close();
                } catch (Exception e) {
                    LOG.warn("Failed to close resource: {}", v.getClass().getName(), e);
                }
            }
        })));
    }

    /**
     * Seals the runtime around the container's last lifecycle pass, in three
     * steps that must not be collapsed into two.
     *
     * <p>{@code markClosed} runs under the realize lock: the closed flag is what
     * every publishing path re-checks under that same lock, so once it is up,
     * nothing can be minted or realized into the caches again — which is what
     * makes the clear below safe even though it now happens later. A realization
     * that passed its first closed check and is already constructing holds the
     * lock, so it finishes before {@code markClosed} runs and its target is in
     * the cache when {@code finalDrain} snapshots.
     *
     * <p>{@code finalDrain} — user {@code @PreDestroy} and {@code close()}
     * callbacks — runs OUTSIDE the lock, deliberately, per the rule this lock
     * documents: a callback may join a worker thread that is itself resolving a
     * service, and that thread needs the lock to mint or realize. Holding it here
     * would deadlock the shutdown. A resolver racing the drain cannot publish
     * (the flag is already up), so it fails its re-check and throws rather than
     * waiting for the drain to finish.
     */
    RuntimeException seal(Runnable markClosed, Supplier<RuntimeException> finalDrain) {
        synchronized (realizeLock) {
            markClosed.run();
        }
        RuntimeException failure = finalDrain.get();
        synchronized (realizeLock) {
            proxyCache.clear();
            targetCache.clear();
        }
        return failure;
    }

    /**
     * Runs {@code work} under the circular-dependency guard for {@code key}:
     * a re-entrant realization of the same key on this thread fails fast
     * instead of recursing forever; the guard is always released.
     */
    private <T> T withCycleGuard(ServiceKey key, Supplier<T> work) {
        LinkedHashSet<ServiceKey> stack = realizeStack.get();
        if (!stack.add(key)) {
            throw new IllegalStateException(
                "Circular dependency detected: " + cyclePath(stack, key)
            );
        }
        try {
            return work.get();
        } finally {
            stack.remove(key);
        }
    }

    /**
     * Path from the first occurrence of {@code key} to the re-entry closing
     * the cycle: {@code A → B → A}. Outer frames left out.
     */
    private static String cyclePath(Set<ServiceKey> stack, ServiceKey key) {
        StringBuilder path = new StringBuilder();
        boolean inCycle = false;
        for (ServiceKey frame : stack) {
            if (!inCycle && !frame.equals(key)) {
                continue;
            }
            inCycle = true;
            appendFrame(path, frame);
        }
        appendFrame(path, key);
        return path.toString();
    }

    private static void appendFrame(StringBuilder path, ServiceKey frame) {
        if (path.length() > 0) {
            path.append(" → ");
        }
        path.append(frame.type().getName()).append(" (id ").append(frame.id()).append(')');
    }

    /**
     * The proxy for an interface binding. A PROTOTYPE target must not route
     * through {@link #realize}: that path caches in {@code targetCache}, which
     * would share one target across every proxy and pull the prototype into
     * container-close lifecycle. Its proxy instead creates ONE target lazily
     * (first method call) and reuses it — matching the unadvised prototype
     * semantics of "one instance per get(), state persists across calls" —
     * without sharing across proxies or entering the container's caches.
     */
    private <T> T proxy(BindingImpl<T> binding) {
        boolean perProxyTarget = binding.scope() == Scope.PROTOTYPE;
        Supplier<T> target = perProxyTarget
            ? binding::directInstance
            : () -> realize(binding);
        return ServiceProxy.create(
            binding.type(),
            target,
            binding.type().getSimpleName() + "[" + binding.id() + "]",
            binding.advices(),
            perProxyTarget
        );
    }

    /** A thread-scope cache key owned by one container's runtime. */
    private record ScopeKey(ServiceRuntime owner, ServiceKey key) {}
}
