package com.jujin.freeway.ioc;
import java.io.ByteArrayOutputStream;
import java.io.NotSerializableException;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import com.jujin.freeway.commons.scoped.ScopedCache;
import com.jujin.freeway.ioc.advisor.MethodInvocation;
import com.jujin.freeway.ioc.annotation.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static com.jujin.freeway.ioc.FreewayTestSupport.*;

/** ScopeProxyAdvisorTest: split from the former FreewayTest monolith (behavior-preserving move). */
class ScopeProxyAdvisorTest {
    @BeforeEach
    void captureSystemProperties() { FreewayTestSupport.capture(); }

    @AfterEach
    void restoreSystemProperties() { FreewayTestSupport.restore(); }

    @Test
    void standaloneScopedCacheValueIsNotClosedByContainer() {
        Container container = Freeway.create(binder -> {});
        List<AutoCloseable> closed = new ArrayList<>();

        ScopedCache.within(() -> {
            ScopedCache.get("k", () -> (AutoCloseable) () -> closed.add(null));
            return null;
        });

        container.close();
        assertTrue(closed.isEmpty(),
            "standalone ScopedCache values must not be closed by the container hook");
    }

    @Test
    void advisedPrototypeKeepsStateAcrossCallsOnSameProxy() {
        // Regression: an advised PROTOTYPE proxy created a NEW target on every
        // method call, so state set on the first call was lost on the second —
        // unlike an unadvised prototype ("one instance per get(), state
        // persists across calls"). The proxy must lazily create ONE target per
        // proxy and reuse it.
        StatefulCounterImpl.created.set(0);
        StatefulCounterImpl.failNextConstruction = false;
        Container container = Freeway.create(binder ->
            binder.bind(StatefulCounter.class)
                  .to(StatefulCounterImpl.class)
                  .scope(Scope.PROTOTYPE)
                  .advise(advisor -> advisor.wrap(inv -> true, MethodInvocation::proceed)));

        StatefulCounter p1 = container.get(StatefulCounter.class);
        p1.bump();
        p1.bump();
        p1.bump();
        assertEquals(3, p1.value(),
            "state set through one proxy must persist across calls on that proxy");

        StatefulCounter p2 = container.get(StatefulCounter.class);
        assertEquals(0, p2.value(), "each get() must yield a fresh instance");
        p2.bump();
        assertEquals(1, p2.value());
        assertEquals(3, p1.value(), "the first proxy's state must be untouched by p2");

        assertEquals(2, StatefulCounterImpl.created.get(),
            "exactly one target per proxy (one per get())");
        container.close();
    }

    @Test
    void advisedPrototypeRetriesFailedConstruction() {
        // A throwing target construction must NOT be cached: the next call on
        // the same proxy retries instead of reusing a failed (or missing) target.
        StatefulCounterImpl.created.set(0);
        StatefulCounterImpl.failNextConstruction = true;
        Container container = Freeway.create(binder ->
            binder.bind(StatefulCounter.class)
                  .to(StatefulCounterImpl.class)
                  .scope(Scope.PROTOTYPE)
                  .advise(advisor -> advisor.wrap(inv -> true, MethodInvocation::proceed)));

        StatefulCounter p1 = container.get(StatefulCounter.class);
        RuntimeException failure = assertThrows(RuntimeException.class, p1::bump,
            "the failed construction must propagate");
        Throwable cause = failure;
        while (cause != null && !(cause instanceof IllegalStateException)) {
            cause = cause.getCause();
        }
        assertInstanceOf(IllegalStateException.class, cause,
            "the simulated construction failure must surface in the cause chain");
        p1.bump();
        assertEquals(1, p1.value(),
            "the next call must retry construction instead of reusing the failed attempt");
        assertEquals(1, StatefulCounterImpl.created.get());
        container.close();
    }

    @Test
    void singletonThroughIndirectInterfaceRejectsThreadScopedDependency() {
        // Bind ExtendedServiceImpl under BaseService — an indirect interface.
        // ExtendedServiceImpl extends ExtendedService extends BaseService.
        // findOwnerBinding must walk ExtendedService → BaseService to find the binding.
        Container container = Freeway.create(binder -> {
            binder.bind(BaseService.class).to(ExtendedServiceImpl.class).scope(Scope.SINGLETON);
            binder.bind(ThreadScopedDep.class).to(ThreadScopedDep.class).scope(Scope.THREAD);
        });

        // trigger proxy realization via ScopedCache (thread scope)
        assertThrows(RuntimeException.class, () ->
            ScopedCache.within(() -> {
                BaseService svc = container.get(BaseService.class);
                svc.name(); // triggers proxy → realize → scope check
            }));
    }

    @Test
    void singletonThroughInterfaceRejectsThreadScopedDependency() {
        Container container = Freeway.create(binder -> {
            binder.bind(SingletonService.class).to(SingletonServiceImpl.class).scope(Scope.SINGLETON);
            binder.bind(ThreadScopedDep.class).to(ThreadScopedDep.class).scope(Scope.THREAD);
        });

        assertThrows(RuntimeException.class, () ->
            container.create(ConsumerWithThreadDep.class),
                "Singleton injected with thread-scoped dep should fail");
    }

    @Test
    void prototypeBindingHonorsAdvice() {
        List<String> calls = new ArrayList<>();
        PrototypeGreeterImpl.created.set(0);
        Container container = Freeway.create(binder ->
            binder.bind(PrototypeGreeter.class)
                  .to(PrototypeGreeterImpl.class)
                  .scope(Scope.PROTOTYPE)
                  .advise(advisor -> advisor.wrap(
                      inv -> true,
                      inv -> {
                          calls.add("advised");
                          return inv.proceed();
                      })));

        PrototypeGreeter g1 = container.get(PrototypeGreeter.class);
        PrototypeGreeter g2 = container.get(PrototypeGreeter.class);
        g1.greet();
        g2.greet();

        assertEquals(List.of("advised", "advised"), calls,
                "advice should fire on every PROTOTYPE resolution");
        assertNotSame(g1, g2, "PROTOTYPE should create new instance each time");
        assertEquals(2, PrototypeGreeterImpl.created.get(),
            "PROTOTYPE+advice proxies must not share one cached target");
    }

    @Test
    void proxyObjectSemanticsAreIdentityBased() {
        // Singleton: every get() returns the SAME proxy.
        Container container = Freeway.create(binder ->
            binder.bind(Greeter.class).to(GreeterImpl.class));
        Greeter g1 = container.get(Greeter.class);
        Greeter g2 = container.get(Greeter.class);
        assertSame(g1, g2, "singleton proxies are cached");

        assertEquals(g1, g1, "a proxy equals itself");
        assertNotEquals(g1, new GreeterImpl(), "a proxy never equals the real implementation");
        assertEquals(System.identityHashCode(g1), g1.hashCode(),
            "hashCode is identity-based");
        assertTrue(g1.toString().contains("Greeter"),
            "toString describes the proxied type, got: " + g1);
        // Proxies extend Proxy (which is Serializable), so
        // instanceof Serializable is true — but serialization fails because
        // the invocation handler is not serializable.
        assertTrue(g1 instanceof Serializable,
            "the Proxy base class is Serializable");
        assertThrows(NotSerializableException.class, () -> {
            try (var out = new ObjectOutputStream(new ByteArrayOutputStream())) {
                out.writeObject(g1);
            }
        }, "serializing a proxy must fail on the non-serializable handler");
        container.close();

        // PROTOTYPE+advice: every get() returns a DISTINCT proxy.
        Container proto = Freeway.create(binder ->
            binder.bind(PrototypeGreeter.class)
                  .to(PrototypeGreeterImpl.class)
                  .scope(Scope.PROTOTYPE)
                  .advise(advisor -> advisor.wrap(inv -> true, MethodInvocation::proceed)));
        PrototypeGreeter p1 = proto.get(PrototypeGreeter.class);
        PrototypeGreeter p2 = proto.get(PrototypeGreeter.class);
        assertNotEquals(p1, p2, "distinct proxies are not equal");
        proto.close();
    }

    @Test
    void adviceChainSupportsMultipleAdvisorsAndShortCircuit() {
        List<String> log = new ArrayList<>();

        Container container = Freeway.create(binder ->
            binder.bind(Greeter.class)
                .to(GreeterImpl.class)
                .advise(advisor -> advisor.wrap(
                    invocation -> invocation.method().getName().equals("greet"),
                    invocation -> {
                        log.add("before-first");
                        try {
                            return invocation.proceed();
                        } finally {
                            log.add("after-first");
                        }
                    }
                ))
                .advise(advisor -> advisor.wrap(
                    invocation -> invocation.method().getName().equals("greet"),
                    invocation -> {
                        log.add("short-circuit");
                        return "short-circuited";
                        // does NOT call proceed() - short circuits
                    }
                ))
        );

        Greeter service = container.get(Greeter.class);
        assertEquals("short-circuited", service.greet());
        // after-first fires because first advisor's proceed() returned after second short-circuited
        assertEquals(List.of("before-first", "short-circuit", "after-first"), log);
        assertEquals(1, GreeterImpl.created.get());
    }

    @Test
    void advisorsCanWrapServiceMethods() {
        AtomicInteger before = new AtomicInteger();
        AtomicInteger after = new AtomicInteger();

        Container container = Freeway.create(binder ->
            binder.bind(Greeter.class)
                .to(GreeterImpl.class)
                .advise(advisor -> advisor.wrap(
                    invocation -> invocation.method().getName().equals("greet"),
                    invocation -> {
                        before.incrementAndGet();
                        try {
                            return invocation.proceed();
                        } finally {
                            after.incrementAndGet();
                        }
                    }
                ))
        );

        Greeter service = container.get(Greeter.class);

        assertTrue(Proxy.isProxyClass(service.getClass()));
        assertEquals("hello", service.greet());
        assertEquals(1, before.get());
        assertEquals(1, after.get());
        assertEquals(1, GreeterImpl.created.get());
    }

    @Test
    void prototypeBindingCreatesNewInstanceEachTime() {
        Container container = Freeway.create(binder ->
            binder.bind(Greeter.class).to(GreeterImpl.class).scope(Scope.PROTOTYPE)
        );

        Greeter one = container.get(Greeter.class);
        Greeter two = container.get(Greeter.class);

        // Prototype: each get() returns a new instance
        assertNotEquals(System.identityHashCode(one), System.identityHashCode(two));
        assertEquals(2, GreeterImpl.created.get());
    }

    @Test
    void instanceBindingReturnsTheBoundInstance() {
        GreeterImpl bound = new GreeterImpl();
        Container container = Freeway.create(binder ->
            binder.bind(GreeterImpl.class).to(bound)
        );

        assertSame(bound, container.get(GreeterImpl.class));
    }

    @Test
    void instanceBindingRejectsNonSingletonScopeBeforeTo() {
        GreeterImpl bound = new GreeterImpl();
        assertThrows(IllegalStateException.class, () ->
            Freeway.create(binder ->
                binder.bind(GreeterImpl.class).scope(Scope.PROTOTYPE).to(bound)
            )
        );
    }

    @Test
    void instanceBindingRejectsNonSingletonScopeAfterTo() {
        GreeterImpl bound = new GreeterImpl();
        assertThrows(IllegalStateException.class, () ->
            Freeway.create(binder ->
                binder.bind(GreeterImpl.class).to(bound).scope(Scope.PROTOTYPE)
            )
        );
    }

    @Test
    void scopedBindingReusesWithinScopeAndIsDestroyedOnClose() {
        ScopedCounter.created.set(0);
        ScopedCounter.destroyed.set(0);

        Container container = Freeway.create(binder ->
            binder.bind(ScopedCounter.class).to(ScopedCounter.class).scope(Scope.THREAD)
        );
        Scoping scoping = container.get(Scoping.class);

        assertThrows(IllegalStateException.class, () -> container.get(ScopedCounter.class));

        AtomicReference<ScopedCounter> firstHolder = new AtomicReference<>();
        scoping.within(() -> {
            ScopedCounter first = container.get(ScopedCounter.class);
            firstHolder.set(first);
            ScopedCounter second = container.get(ScopedCounter.class);

            assertSame(first, second);
            assertEquals(1, ScopedCounter.created.get());
            assertEquals(0, ScopedCounter.destroyed.get());
            return null;
        });
        ScopedCounter first = firstHolder.get();

        assertEquals(1, ScopedCounter.destroyed.get());

        scoping.within(() -> {
            ScopedCounter third = container.get(ScopedCounter.class);
            assertNotSame(first, third);
            assertEquals(2, ScopedCounter.created.get());
            return null;
        });

        assertEquals(2, ScopedCounter.destroyed.get());
    }

    @Test
    void scopedBindingWithinUsesScopedValue() {
        ScopedCounter.created.set(0);
        ScopedCounter.destroyed.set(0);

        Container container = Freeway.create(binder ->
            binder.bind(ScopedCounter.class).to(ScopedCounter.class).scope(Scope.THREAD)
        );
        Scoping scoping = container.get(Scoping.class);

        assertThrows(IllegalStateException.class, () -> container.get(ScopedCounter.class));

        int result = scoping.within(() -> {
            ScopedCounter first = container.get(ScopedCounter.class);
            ScopedCounter second = container.get(ScopedCounter.class);
            assertSame(first, second);
            assertEquals(1, ScopedCounter.created.get());
            assertEquals(0, ScopedCounter.destroyed.get());
            return first.id();
        });

        assertEquals(1, ScopedCounter.destroyed.get());

        scoping.within(() -> {
            ScopedCounter third = container.get(ScopedCounter.class);
            assertEquals(2, ScopedCounter.created.get());
            return null;
        });

        assertEquals(2, ScopedCounter.destroyed.get());
    }

    @Test
    void scopedValueNestingShadowsOuter() {
        ScopedCounter.created.set(0);
        ScopedCounter.destroyed.set(0);

        Container container = Freeway.create(binder ->
            binder.bind(ScopedCounter.class).to(ScopedCounter.class).scope(Scope.THREAD)
        );
        Scoping scoping = container.get(Scoping.class);

        scoping.within(() -> {
            ScopedCounter outer = container.get(ScopedCounter.class);
            assertEquals(1, ScopedCounter.created.get());
            assertEquals(0, ScopedCounter.destroyed.get());

            // Nested within() creates a new scope, shadowing the outer
            scoping.within(() -> {
                ScopedCounter inner = container.get(ScopedCounter.class);
                assertNotSame(outer, inner);
                assertEquals(2, ScopedCounter.created.get());
                assertEquals(0, ScopedCounter.destroyed.get()); // outer not yet destroyed
                return null;
            });

            assertEquals(1, ScopedCounter.destroyed.get()); // inner destroyed

            // Outer session still alive
            ScopedCounter stillOuter = container.get(ScopedCounter.class);
            assertSame(outer, stillOuter);
            return null;
        });

        assertEquals(2, ScopedCounter.destroyed.get()); // outer destroyed
    }

    @Test
    void withinPropagatesReturnValue() {
        Container container = Freeway.create(binder ->
            binder.bind(ScopedCounter.class).to(ScopedCounter.class).scope(Scope.THREAD)
        );
        Scoping scoping = container.get(Scoping.class);

        ScopedApi api = scoping.within(() -> container.get(ScopedCounter.class));
        assertNotNull(api);
    }

    @Test
    void withinClosesScopeOnException() {
        ScopedCounter.created.set(0);
        ScopedCounter.destroyed.set(0);

        Container container = Freeway.create(binder ->
            binder.bind(ScopedCounter.class).to(ScopedCounter.class).scope(Scope.THREAD)
        );
        Scoping scoping = container.get(Scoping.class);

        assertThrows(RuntimeException.class, () ->
            scoping.within(() -> {
                container.get(ScopedCounter.class);
                assertEquals(1, ScopedCounter.created.get());
                throw new RuntimeException("boom");
            })
        );

        // Scope must be closed even though the work threw
        assertEquals(1, ScopedCounter.destroyed.get());
    }

    @Test
    void containerCloseAfterWithinReleasesScopes() {
        ScopedCounter.created.set(0);
        ScopedCounter.destroyed.set(0);

        Container container = Freeway.create(binder ->
            binder.bind(ScopedCounter.class).to(ScopedCounter.class).scope(Scope.THREAD)
        );
        Scoping scoping = container.get(Scoping.class);

        scoping.within(() -> {
            container.get(ScopedCounter.class);
            return null;
        });

        assertEquals(1, ScopedCounter.destroyed.get());

        // Container close should be safe after scopes have been cleaned up
        container.close();
    }

    @Test
    void withinRefusesAfterContainerClose() {
        Container container = Freeway.create(binder ->
            binder.bind(ScopedCounter.class).to(ScopedCounter.class).scope(Scope.THREAD)
        );
        Scoping scoping = container.get(Scoping.class);

        container.close();

        assertThrows(IllegalStateException.class, () ->
            scoping.within(() -> null)
        );
    }

    @Test
    void threadScopedProxyRejectsInvocationAfterClose() {
        // Regression: the singleton path throws "Container is closed" from
        // realize() when a proxy obtained before close() is invoked after
        // close, but realizeThreadScoped() had no such check — a THREAD proxy
        // silently realized a fresh value after the container was sealed.
        ScopedCounter.created.set(0);
        ScopedCounter.destroyed.set(0);
        Container container = Freeway.create(binder ->
            binder.bind(ScopedApi.class).to(ScopedCounter.class).scope(Scope.THREAD)
        );
        Scoping scoping = container.get(Scoping.class);
        ScopedApi api = container.get(ScopedApi.class); // lazy THREAD proxy

        scoping.within(() -> {
            container.close();
            IllegalStateException ex = assertThrows(IllegalStateException.class, api::id,
                "invoking a THREAD proxy after close must throw");
            assertTrue(ex.getMessage().contains("Container is closed"),
                "a THREAD proxy invoked after close must report the sealed container, got: "
                    + ex.getMessage());
            return null;
        });

        assertEquals(0, ScopedCounter.created.get(),
            "no thread value may be realized after close");
        assertEquals(0, ScopedCounter.destroyed.get());
    }

    @Test
    void singletonCanInjectScopedInterfaceThroughProxy() {
        ScopedCounter.created.set(0);
        ScopedCounter.destroyed.set(0);

        Container container = Freeway.create(
            binder -> {
                binder.bind(ScopedApi.class).to(ScopedCounter.class).scope(Scope.THREAD);
                binder.bind(ScopedSingletonService.class).to(ScopedSingletonService.class);
            }
        );
        Scoping scoping = container.get(Scoping.class);

        ScopedSingletonService service = container.get(ScopedSingletonService.class);
        assertTrue(service.proxied());

        int firstId = scoping.within(() -> {
            int id = service.currentId();
            assertEquals(id, service.currentId()); // same scope, same id
            return id;
        });

        int secondId = scoping.within(service::currentId);
        assertNotEquals(firstId, secondId);

        assertEquals(2, ScopedCounter.created.get());
        assertEquals(2, ScopedCounter.destroyed.get());
    }

    @Test
    void singletonRejectsDirectInjectionOfThreadScopedConcrete() {
        Container container = Freeway.create(
            binder -> {
                binder.bind(ScopedCounter.class).to(ScopedCounter.class).scope(Scope.THREAD);
                binder.bind(ScopedSingleton.class).to(ScopedSingleton.class);
            }
        );

        RuntimeException ex = assertThrows(RuntimeException.class, () -> container.get(ScopedSingleton.class));
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("cannot directly inject thread-scoped concrete"),
            "the dedicated scope-compat diagnostic must be reachable even when no scope is open "
                + "(not masked by 'No open scope' from realization)");
    }

    @Test
    void singletonRejectsInjectionOfNotThreadSafeConcrete() {
        Container container = Freeway.create(binder -> {
            binder.bind(UnsafeShared.class).to(UnsafeShared.class);
            binder.bind(SingletonHoldingUnsafe.class).to(SingletonHoldingUnsafe.class);
        });

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> container.get(SingletonHoldingUnsafe.class));
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("@NotThreadSafe"),
            "a singleton holder must be rejected for @NotThreadSafe deps, got: "
                + ex.getCause().getMessage());
    }

    @Test
    void prototypeHolderMayInjectNotThreadSafeConcrete() {
        Container container = Freeway.create(binder -> {
            binder.bind(UnsafeShared.class).to(UnsafeShared.class);
            binder.bind(PrototypeHoldingUnsafe.class)
                .to(PrototypeHoldingUnsafe.class)
                .scope(Scope.PROTOTYPE);
        });

        assertDoesNotThrow(() -> container.get(PrototypeHoldingUnsafe.class),
            "a prototype holder gets its own instance per resolution — no sharing");
    }

    @Test
    void conflictingConcurrencyMarkersRejected() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> Freeway.create(binder ->
                binder.bind(ConflictingContract.class).to(ConflictingContract.class)));
        assertTrue(ex.getMessage().contains("both @ThreadSafe and @NotThreadSafe"),
            "got: " + ex.getMessage());
    }

    @Test
    void threadSafeMarkerResolvesByMarker() {
        Container container = Freeway.create(binder ->
            binder.bind(Greeter.class).to(ThreadSafeGreeterImpl.class));

        Greeter g = container.get(Greeter.class, ThreadSafe.class);
        assertNotNull(g);
        assertEquals("safe", g.greet());
    }

    @Test
    void rejectsAdvisorOnNonInterfaceType() {
        Container container = Freeway.create(binder ->
            binder.bind(GreeterImpl.class)
                .to(GreeterImpl.class)
                .advise(advisor -> advisor.wrap(
                    invocation -> true,
                    invocation -> invocation.proceed()
                ))
        );
        assertThrows(IllegalArgumentException.class,
            () -> container.get(GreeterImpl.class));
    }
}
