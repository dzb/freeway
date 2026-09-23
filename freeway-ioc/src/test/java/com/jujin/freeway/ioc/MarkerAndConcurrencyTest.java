package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.annotation.*;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static com.jujin.freeway.ioc.FreewayTestSupport.*;

/** MarkerAndConcurrencyTest: split from the former FreewayTest monolith (behavior-preserving move). */
class MarkerAndConcurrencyTest {
    @BeforeEach
    void captureSystemProperties() { FreewayTestSupport.capture(); }

    @AfterEach
    void restoreSystemProperties() { FreewayTestSupport.restore(); }

    @Test
    void markerAnnotationResolvesCorrectService() {
        Container container = Freeway.create(binder -> {
            binder.bind(Cache.class).to(FastCache.class).marker(Fast.class);
            binder.bind(Cache.class).to(SlowCache.class).marker(Slow.class);
        });

        // By-marker resolution via container.get(type, markers...)
        Cache cache = container.get(Cache.class, Fast.class);
        assertEquals("fast", cache.name());

        // Injection point with @Fast marker
        CacheConsumer consumer = container.create(CacheConsumer.class);
        assertEquals("fast", consumer.cacheName());
    }

    @Test
    void primaryAlsoAddsMarker() {
        Container container = Freeway.create(binder -> {
            binder.bind(Cache.class).to(FastCache.class).primary();
            binder.bind(Cache.class).to(SlowCache.class);
        });

        // .primary() should add @Primary as a marker, so get(type, Primary.class) works
        Cache cache = container.get(Cache.class, Primary.class);
        assertEquals("fast", cache.name());
    }

    @Test
    void primaryAnnotationOnImplementationSelectsTheBinding() {
        // Regression: @Primary on the implementation class entered the marker
        // index but not the primary flag type resolution read, so get(type)
        // over two bindings failed as ambiguous although @Primary documents
        // itself as equivalent to .primary().
        Container container = Freeway.create(binder -> {
            binder.bind(Cache.class).to(SlowCache.class);
            binder.bind(Cache.class).to(PrimaryCache.class);
        });

        assertEquals("primary", container.get(Cache.class).name());
        assertTrue(container.isActiveBinding(Cache.class, Primary.class));
        container.close();
    }

    @Primary
    static final class PrimaryCache implements Cache {
        @Override
        public String name() {
            return "primary";
        }
    }

    @Test
    void activeBindingQueryFollowsUniqueAndPrimarySelection() {
        Container primary = Freeway.create(binder -> {
            binder.bind(Cache.class).to(FastCache.class).marker(Fast.class);
            binder.bind(Cache.class).to(SlowCache.class).marker(Slow.class).primary();
        });
        assertFalse(primary.isActiveBinding(Cache.class, Fast.class),
            "a non-primary marked binding is not the selected binding");
        assertTrue(primary.isActiveBinding(Cache.class, Slow.class),
            "the primary binding is the selected binding");

        Container unique = Freeway.create(binder ->
            binder.bind(Cache.class).to(FastCache.class).marker(Fast.class));
        assertTrue(unique.isActiveBinding(Cache.class, Fast.class),
            "the only binding is the selected binding");
        assertFalse(unique.isActiveBinding(Cache.class, Slow.class),
            "an absent marker must not match the selected binding");
        primary.close();
        unique.close();
    }

    @Test
    void markerDeclaredAfterFlushIsRejected() {
        // Bindings are sealed when their module's bindings flush: a handle
        // shared with a LATER module cannot gain markers after registration.
        // Declare the marker in the owning module's bind instead.
        Binding<Cache>[] shared = new Binding[1];
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> Freeway.create(
                binder -> shared[0] = binder.bind(Cache.class).to(FastCache.class),
                binder -> shared[0].marker(Fast.class)),
            "a sealed binding must reject a late marker declaration");
        assertTrue(ex.getMessage().contains("sealed"),
            "the error must name the cause, got: " + ex.getMessage());
    }

    @Test
    void markerResolutionRejectsAmbiguousMatch() {
        Container container = Freeway.create(binder -> {
            binder.bind(Cache.class).to(FastCache.class).marker(Fast.class);
            binder.bind(Cache.class).to(SlowCache.class).marker(Fast.class); // same marker!
        });

        assertThrows(IllegalArgumentException.class, () ->
                container.get(Cache.class, Fast.class));
    }

    @Test
    void annotationTargetsDeclareOnlyPositionsTheFrameworkReads() {
        // A declared position that no reader consults is an affordance the
        // framework does not keep: @Marker and @Primary are read on TYPE
        // (module/implementation classes) and, at an injection point, on
        // FIELD/PARAMETER through the marker index; @Builtin is a marker
        // applied by module-level @Marker(Builtin.class) or .marker(...), so
        // annotating a class with it does nothing. There is no producer-method
        // binding to read a METHOD position.
        assertEquals(Set.of(ElementType.TYPE), targets(Marker.class));
        assertEquals(Set.of(ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER),
            targets(Primary.class));
        assertEquals(Set.of(ElementType.FIELD, ElementType.PARAMETER), targets(Builtin.class));
    }

    private static Set<ElementType> targets(Class<?> annotation) {
        return Set.of(annotation.getAnnotation(Target.class).value());
    }

    @Test
    void builtinMarkerPropagatesToCoreServices() {
        Container container = Freeway.create();
        // Core services registered via registerBuiltin() should carry @Builtin
        var symbols = container.get(SymbolSource.class, Builtin.class);
        assertNotNull(symbols);
    }

    @Test
    void moduleLevelMarkerPropagatesToBindings() {
        Container container = Freeway.create(new MarkerTestModule());

        // The Cache binding should inherit @Builtin from the module
        Cache cache = container.get(Cache.class, Builtin.class);
        assertEquals("fast", cache.name());
    }

    @Test
    void eventBusConcurrentPublishAndSubscribe() throws Exception {
        Container container = Freeway.create(binder -> {
            binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(String.class, e -> {}));
        });
        EventBus bus = container.get(EventBus.class);
        int threads = 8;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> error = new AtomicReference<>();
        try {
            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            bus.publish("evt-" + threadId + "-" + i);
                            if (i % 50 == 0) {
                                var sub = bus.subscribe(String.class, e -> {});
                                bus.unsubscribe(sub);
                            }
                        }
                    } catch (Throwable ex) {
                        error.compareAndSet(null, ex);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS),
                "concurrent pub/sub must complete within 10s");
        } finally {
            pool.shutdownNow();
            container.close();
        }
        assertNull(error.get(), "concurrent pub/sub failure: " + error.get());
    }
}
