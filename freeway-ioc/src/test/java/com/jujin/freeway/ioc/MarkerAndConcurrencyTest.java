package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.annotation.*;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void markerDeclaredAfterFlushStillResolves() {
        // Regression: a binding flushed by a nested install, then receiving
        // .marker() afterwards, was invisible to marker-based resolution —
        // the MarkerIndex was never updated for the late declaration.
        Container container = Freeway.create(binder -> {
            Binding<Cache> binding = binder.bind(Cache.class).to(FastCache.class);
            binder.install(ignored -> {});  // flush triggers registration
            binding.marker(Fast.class);      // late marker on a registered binding
        });

        Cache cache = container.get(Cache.class, Fast.class);
        assertEquals("fast", cache.name());
        container.close();
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
