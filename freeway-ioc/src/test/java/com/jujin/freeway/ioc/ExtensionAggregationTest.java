package com.jujin.freeway.ioc;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import com.jujin.freeway.ioc.annotation.*;
import com.jujin.freeway.ioc.extension.Contribution;
import com.jujin.freeway.ioc.extension.Extension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static com.jujin.freeway.ioc.FreewayTestSupport.*;

/** ExtensionAggregationTest: split from the former FreewayTest monolith (behavior-preserving move). */
class ExtensionAggregationTest {
    @BeforeEach
    void captureSystemProperties() { FreewayTestSupport.capture(); }

    @AfterEach
    void restoreSystemProperties() { FreewayTestSupport.restore(); }

    @Test
    void extensionsAggregateContributions() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeature.class).add(new AppFeature("core")),
            binder -> binder.contribute(AppFeature.class).add(new AppFeature("web"))
        );

        ListFeatureCatalog config = container.create(ListFeatureCatalog.class);

        assertEquals(List.of("core", "web"), config.featureNames());
    }

    @Test
    void extensionConcurrentReadsSurviveCacheInvalidation() throws Exception {
        // all()/asMap()/get() are lock-free after warm-up (volatile double-check).
        // A mid-flight add() must invalidate the caches without corrupting
        // concurrent readers.
        Extension<AppFeature> ext = new Extension<>(AppFeature.class);
        ext.add("core", new AppFeature("core"));
        ext.add("web", new AppFeature("web"));

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicInteger added = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < 500; j++) {
                            int size = ext.all().size();
                            assertTrue(size >= 2 && size <= 3,
                                "size must stay in [2,3] during mutation, got " + size);
                            assertTrue(ext.asMap().containsKey("core"));
                            assertTrue(ext.get("core").isPresent());
                            assertTrue(ext.get("web").isPresent());
                            if (j == 250 && added.getAndIncrement() == 0) {
                                ext.add("db", new AppFeature("db")); // invalidate mid-flight
                            }
                        }
                    } catch (Throwable t) {
                        error.compareAndSet(null, t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS),
                "concurrent extension reads must complete within 10s");
        } finally {
            pool.shutdownNow();
        }
        assertNull(error.get(), "concurrent read failure: " + error.get());
        assertEquals(List.of("core", "web", "db"), ext.all().stream()
            .map(AppFeature::name).toList(), "final state must include the mid-flight addition");
    }

    @Test
    void parameterExtensionsAggregateContributions() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeature.class).add(new AppFeature("core")),
            binder -> binder.contribute(AppFeature.class).add(new AppFeature("web"))
        );

        ListFeatureCatalog config = container.create(ListFeatureCatalog.class);

        assertEquals(List.of("core", "web"), config.featureNames());
    }

    @Test
    void extensionsCanOrderContributionsById() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeature.class)
                .add("web", new AppFeature("web"))
                .after("db", "metrics"),
            binder -> binder.contribute(AppFeature.class)
                .add("core", new AppFeature("core")),
            binder -> binder.contribute(AppFeature.class)
                .add("db", new AppFeature("db"))
                .after("core"),
            binder -> binder.contribute(AppFeature.class)
                .add("metrics", new AppFeature("metrics"))
                .before("web")
        );

        ListFeatureCatalog config = container.create(ListFeatureCatalog.class);

        assertEquals(List.of("core", "db", "metrics", "web"), config.featureNames());
    }

    @Test
    void extensionOrderingRejectsDuplicateIds() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> Freeway.create(
            binder -> binder.contribute(AppFeature.class).add("same", new AppFeature("first")),
            binder -> binder.contribute(AppFeature.class).add("same", new AppFeature("second"))
        ));

        assertTrue(ex.getMessage().contains("Duplicate contribution id same"));
    }

    @Test
    void extensionOrderingIgnoresMissingIds() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeature.class)
                .add("first", new AppFeature("first"))
                .after("missing")
                .after("second"),
            binder -> binder.contribute(AppFeature.class)
                .add("second", new AppFeature("second"))
        );

        ListFeatureCatalog config = container.create(ListFeatureCatalog.class);
        assertEquals(List.of("second", "first"), config.featureNames(),
            "a missing id must be ignored, not fail ordering");
    }

    @Test
    void extensionOrderingWarnsOnMissingIds() {
        var records = new ArrayList<LogRecord>();
        java.util.logging.Logger jul = java.util.logging.Logger.getLogger(
            "com.jujin.freeway.ioc.extension.Extension");
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        jul.addHandler(handler);
        try {
            Container container = Freeway.create(
                binder -> binder.contribute(AppFeature.class)
                    .add("first", new AppFeature("first"))
                    .after("missing")
            );
            container.extension(AppFeature.class).all();

            assertTrue(records.stream().anyMatch(r ->
                    r.getLevel() == java.util.logging.Level.WARNING
                        && r.getMessage() != null
                        && r.getMessage().contains("missing")),
                "missing ordering ids must produce a warning");
        } finally {
            jul.removeHandler(handler);
        }
    }

    @Test
    void extensionOrderingRejectsCycles() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeature.class)
                .add("first", new AppFeature("first"))
                .after("second"),
            binder -> binder.contribute(AppFeature.class)
                .add("second", new AppFeature("second"))
                .after("first")
        );

        Throwable ex = assertThrows(Throwable.class, () -> container.create(ListFeatureCatalog.class));

        Throwable root = ex;
        while (root.getCause() != null) root = root.getCause();
        assertTrue(
            root.getMessage().contains("Contribution order cycle detected"),
            "Expected cycle detection message, got: " + root.getMessage()
        );
    }

    @Test
    void fieldExtensionsOverrideTypeDefault() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeature.class).add(new AppFeature("core")),
            binder -> binder.contribute(AppFeature.class).add(new AppFeature("web")),
            binder -> binder.contribute(AppFlagEntry.class).add("debug", new AppFlagEntry("debug", new AppFlag("debug", true))),
            binder -> binder.contribute(AppFlagEntry.class).add("timing", new AppFlagEntry("timing", new AppFlag("timing", false)))
        );

        MixedExtensionCatalog catalog = container.create(MixedExtensionCatalog.class);

        assertEquals(List.of("core", "web"), catalog.featureNames());
        assertEquals(Map.of("debug", new AppFlag("debug", true), "timing", new AppFlag("timing", false)), catalog.flags());
    }

    @Test
    void extensionEntriesPreserveKeys() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeature.class).add(new AppFeature("core")),
            binder -> binder.contribute(AppFlagEntry.class).add("debug", new AppFlagEntry("debug", new AppFlag("debug", true))),
            binder -> binder.contribute(AppFlagEntry.class).add("audit-special", new AppFlagEntry("audit-special", new AppFlag("audit-special", true)))
        );

        MixedExtensionCatalog catalog = container.create(MixedExtensionCatalog.class);

        assertEquals(Map.of("debug", new AppFlag("debug", true), "audit-special", new AppFlag("audit-special", true)), catalog.flags());
    }

    @Test
    void extensionEntriesSupportNonStringKeys() {
        Container container = Freeway.create(
            binder -> binder.contribute(EnumAppFlagEntry.class).add("debug", new EnumAppFlagEntry(FlagKey.DEBUG, new AppFlag("debug", true))),
            binder -> binder.contribute(EnumAppFlagEntry.class).add("timing", new EnumAppFlagEntry(FlagKey.TIMING, new AppFlag("timing", false)))
        );

        EnumKeyExtensionCatalog catalog = container.create(EnumKeyExtensionCatalog.class);

        assertEquals(Map.of("debug", new AppFlag("debug", true), "timing", new AppFlag("timing", false)), catalog.flags());
    }

    @Test
    void listInjectionFromExtensionParam() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeature.class).add(new AppFeature("core")),
            binder -> binder.contribute(AppFeature.class).add(new AppFeature("web"))
        );
        ListFeatureCatalog catalog = container.create(ListFeatureCatalog.class);
        assertEquals(List.of("core", "web"), catalog.featureNames());
    }

    @Test
    void listInjectionFromExtensionField() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeature.class).add(new AppFeature("core")),
            binder -> binder.contribute(AppFeature.class).add(new AppFeature("web"))
        );
        FieldListFeatureCatalog catalog = container.create(FieldListFeatureCatalog.class);
        assertEquals(List.of("core", "web"), catalog.featureNames());
    }

    @Test
    void mapInjectionFromExtensionParam() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeature.class).add("core", new AppFeature("core")),
            binder -> binder.contribute(AppFeature.class).add("web", new AppFeature("web"))
        );
        MapFeatureCatalog catalog = container.create(MapFeatureCatalog.class);
        assertEquals(Map.of("core", new AppFeature("core"), "web", new AppFeature("web")), catalog.features());
    }

    @Test
    void mapInjectionFromExtensionField() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeature.class).add("core", new AppFeature("core")),
            binder -> binder.contribute(AppFeature.class).add("web", new AppFeature("web"))
        );
        FieldMapFeatureCatalog catalog = container.create(FieldMapFeatureCatalog.class);
        assertEquals(Map.of("core", new AppFeature("core"), "web", new AppFeature("web")), catalog.features());
    }

    @Test
    void mapInjectionExcludesUnnamedContributions() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeature.class).add(new AppFeature("unnamed")),
            binder -> binder.contribute(AppFeature.class).add("named", new AppFeature("named"))
        );
        MapFeatureCatalog catalog = container.create(MapFeatureCatalog.class);
        assertEquals(Map.of("named", new AppFeature("named")), catalog.features());
    }

    @Test
    void extensionInjectionRejectedConstructor() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeature.class).add("core", new AppFeature("core"))
        );
        Throwable ex = assertThrows(Throwable.class, () ->
            container.create(ExtensionConstructorInjection.class));
        assertTrue(ex.getCause().getMessage().contains("Extension<V> is not injectable"));
    }

    @Test
    void extensionInjectionRejectedField() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeature.class).add("core", new AppFeature("core"))
        );
        Throwable ex = assertThrows(Throwable.class, () ->
            container.create(ExtensionFieldInjection.class));
        assertTrue(ex.getCause().getMessage().contains("Extension<V> is not injectable"));
    }

    @Test
    void containerNotInjectableByField() {
        Container container = Freeway.create(binder -> {});
        Throwable ex = assertThrows(Throwable.class, () ->
            container.create(ContainerFieldInjection.class));
        assertTrue(ex.getCause().getMessage().contains("No service registered for type"),
            "Got: " + ex.getCause().getMessage());
    }

    @Test
    void containerNotInjectableByConstructor() {
        Container container = Freeway.create(binder -> {});
        Throwable ex = assertThrows(Throwable.class, () ->
            container.create(ContainerConstructorInjection.class));
        assertTrue(ex.getCause().getMessage().contains("No service registered for type"),
            "Got: " + ex.getCause().getMessage());
    }
}
