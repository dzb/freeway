package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.annotation.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static com.jujin.freeway.ioc.FreewayTestSupport.*;

/** ContainerBindingTest: split from the former FreewayTest monolith (behavior-preserving move). */
class ContainerBindingTest {
    @BeforeEach
    void captureSystemProperties() { FreewayTestSupport.capture(); }

    @AfterEach
    void restoreSystemProperties() { FreewayTestSupport.restore(); }

    @Test
    void bindsServicesAndResolvesById() {
        Container container = Freeway.create(binder -> binder.bind(Greeter.class).to(GreeterImpl.class).id("primary"));

        Greeter service = container.get(Greeter.class);
        Greeter namedService = container.get(Greeter.class, "primary");

        assertTrue(Proxy.isProxyClass(service.getClass()));
        assertSame(service, namedService);
        assertEquals("hello", service.greet());
        assertEquals(1, GreeterImpl.created.get());
    }

    @Test
    void rejectsBlankServiceId() {
        assertThrows(IllegalArgumentException.class, () ->
            Freeway.create(binder -> binder.bind(Greeter.class).to(GreeterImpl.class).id("   "))
        );

        Container container = Freeway.create(binder -> binder.bind(Greeter.class).to(GreeterImpl.class));
        assertThrows(IllegalArgumentException.class, () -> container.get(Greeter.class, "   "));
    }

    @Test
    void resolvesPrimaryBindingWhenNoIdIsProvided() {
        Container container = Freeway.create(
            binder -> binder.bind(PaymentGateway.class).to(StripeGateway.class).id("stripe").primary(),
            binder -> binder.bind(PaymentGateway.class).to(PaypalGateway.class).id("paypal")
        );

        CheckoutService service = container.create(CheckoutService.class);

        assertEquals("stripe", service.gatewayName());
        assertEquals("stripe", container.get(PaymentGateway.class).name());
    }

    @Test
    void resolvesUniqueAssignableBindingWhenNoExactBindingExists() {
        Container container = Freeway.create(
            binder -> binder.bind(GreeterImpl.class).to(GreeterImpl.class)
        );

        Greeter service = container.get(Greeter.class);

        assertInstanceOf(GreeterImpl.class, service);
        assertEquals("hello", service.greet());
    }

    @Test
    void exactBindingTakesPriorityOverAssignableBinding() {
        Container container = Freeway.create(
            binder -> binder.bind(Greeter.class).to(GreeterImpl.class),
            binder -> binder.bind(LoudGreeter.class).to(LoudGreeter.class).primary()
        );

        Greeter service = container.get(Greeter.class);

        assertTrue(Proxy.isProxyClass(service.getClass()));
        assertEquals("hello", service.greet());
    }

    @Test
    void resolvesExplicitNamedServiceInjection() {
        Container container = Freeway.create(
            binder -> binder.bind(PaymentGateway.class).to(StripeGateway.class).id("stripe").primary(),
            binder -> binder.bind(PaymentGateway.class).to(PaypalGateway.class).id("paypal")
        );

        NamedCheckoutService service = container.create(NamedCheckoutService.class);

        assertEquals("paypal", service.gatewayName());
    }

    @Test
    void resolvesExplicitInjectIdSyntaxSugar() {
        Container container = Freeway.create(
            binder -> binder.bind(PaymentGateway.class).to(StripeGateway.class).id("stripe").primary(),
            binder -> binder.bind(PaymentGateway.class).to(PaypalGateway.class).id("paypal")
        );

        InjectNamedCheckoutService service = container.create(InjectNamedCheckoutService.class);

        assertEquals("paypal", service.gatewayName());
    }

    @Test
    void lateIdChangeMigratesRealizedInstance() {
        // Regression: Binding.id() called AFTER the binding was realized re-keys
        // the binding index but used to leave the old-key cache entries behind —
        // get(new id) then realized a SECOND singleton, so close() ran
        // @PreDestroy twice on two instances. The cache must migrate with the
        // re-key so the binding keeps serving ONE instance.
        IdChangeService.destroyed.set(0);
        AtomicReference<Binding<IdChangeService>> handle = new AtomicReference<>();
        Container container = Freeway.create(binder -> {
            Binding<IdChangeService> binding =
                binder.bind(IdChangeService.class).to(IdChangeService.class).id("initial");
            handle.set(binding);
        });

        IdChangeService original = container.get(IdChangeService.class, "initial");
        handle.get().id("renamed"); // late re-key after realization

        IdChangeService renamed = container.get(IdChangeService.class, "renamed");
        assertSame(original, renamed,
            "a re-keyed binding must keep serving the same realized instance");
        assertEquals(0, IdChangeService.destroyed.get());
        assertThrows(IllegalArgumentException.class,
            () -> container.get(IdChangeService.class, "initial"),
            "the old id must no longer resolve after the re-key");

        container.close();
        assertEquals(1, IdChangeService.destroyed.get(),
            "exactly one @PreDestroy for the re-keyed singleton (no second instance)");
    }

    @Test
    void lambdaModulesAreInstalledByIdentity() {
        List<String> log = new ArrayList<>();
        Container container = Freeway.create(
            binder -> { log.add("a"); },
            binder -> { log.add("b"); }
        );
        assertEquals(List.of("a", "b"), log,
                "lambda modules should dedup by identity, not class");
    }

    @Test
    void missingAndAmbiguousBindingsThrowTheStructuredExceptionTypes() {
        Container empty = Freeway.create();

        MissingBindingException byType = assertThrows(
            MissingBindingException.class, () -> empty.get(PaymentGateway.class));
        assertTrue(byType.getMessage().contains("No service registered for type"));

        MissingBindingException byId = assertThrows(
            MissingBindingException.class, () -> empty.get(PaymentGateway.class, "nope"));
        assertTrue(byId.getMessage().contains("and id nope"));

        // Source compatibility: both structured types remain IAEs.
        assertThrows(IllegalArgumentException.class, () -> empty.get(PaymentGateway.class));

        Container ambiguous = Freeway.create(
            binder -> binder.bind(PaymentGateway.class).to(StripeGateway.class),
            binder -> binder.bind(PaymentGateway.class).to(PaypalGateway.class)
        );
        try {
            AmbiguousBindingException abe = assertThrows(
                AmbiguousBindingException.class, () -> ambiguous.get(PaymentGateway.class));
            assertTrue(abe.getMessage().contains("mark one binding as primary()"));
            assertThrows(IllegalArgumentException.class, () -> ambiguous.get(PaymentGateway.class));
        } finally {
            ambiguous.close();
        }
    }

    @Test
    void rejectsMultiplePrimaryBindingsForTheSameType() {
        Container container = Freeway.create(
            binder -> binder.bind(PaymentGateway.class).to(StripeGateway.class).id("stripe").primary(),
            binder -> binder.bind(PaymentGateway.class).to(PaypalGateway.class).id("paypal").primary()
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> container.get(PaymentGateway.class));

        assertTrue(ex.getMessage().contains("Multiple primary services"));
    }

    @Test
    void interfaceBindingsAreLazyUntilInvoked() {
        Container container = Freeway.create(binder -> binder.bind(Greeter.class).to(GreeterImpl.class));

        Greeter service = container.get(Greeter.class);

        assertTrue(Proxy.isProxyClass(service.getClass()));
        assertEquals(0, GreeterImpl.created.get());

        assertEquals("hello", service.greet());
        assertEquals(1, GreeterImpl.created.get());
    }

    @Test
    void rejectsDuplicateBinding() {
        Container container = Freeway.create(binder -> {
            binder.bind(Greeter.class).to(GreeterImpl.class);
            binder.bind(Greeter.class).to(GreeterImpl.class);
        });
        // Multiple bindings of the same type without .primary() is caught at resolve time
        assertThrows(IllegalArgumentException.class, () -> container.get(Greeter.class));
    }

    @Test
    void concurrentFirstGetCreatesSingletonExactlyOnce() throws Exception {
        // Concurrent first resolution must construct the singleton exactly
        // once and return the same instance to every caller — the realize
        // lock (single reentrant lock, not per-key stripes) must serialize
        // first-time creation without deadlocking.
        AtomicInteger constructed = new AtomicInteger();
        Container container = Freeway.create(binder ->
            // Concrete class binding: resolution goes straight to realize()
            // (interface bindings return a lazy proxy, which would defer the
            // provider until a method call and defeat the test).
            binder.bind(ConcurrentServiceImpl.class)
                .to(c -> {
                    constructed.incrementAndGet();
                    return new ConcurrentServiceImpl();
                })
                .scope(Scope.SINGLETON)
        );
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<ConcurrentServiceImpl> results = Collections.synchronizedList(new ArrayList<>());
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        results.add(container.get(ConcurrentServiceImpl.class));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS),
                "concurrent resolution must complete within 10s");
        } finally {
            pool.shutdownNow();
            container.close();
        }
        assertEquals(1, constructed.get(), "singleton must be constructed exactly once");
        ConcurrentServiceImpl first = results.getFirst();
        assertTrue(results.stream().allMatch(r -> r == first),
            "all callers must observe the same singleton instance");
    }

    @Test
    void constructorCycleFailsFast() {
        Container container = Freeway.create(binder -> {
            binder.bind(CycleA.class).to(CycleA.class);
            binder.bind(CycleB.class).to(CycleB.class);
        });
        // InstanceFactory wraps the realize-time error in RuntimeException;
        // the cause chain must surface the circular-dependency diagnostic.
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> container.get(CycleA.class));
        Throwable t = ex;
        boolean found = false;
        while (t != null) {
            if (t.getMessage() != null && t.getMessage().contains("Circular dependency")) {
                found = true;
                break;
            }
            t = t.getCause();
        }
        assertTrue(found, "expected circular dependency in cause chain, got: " + ex);
    }
}
