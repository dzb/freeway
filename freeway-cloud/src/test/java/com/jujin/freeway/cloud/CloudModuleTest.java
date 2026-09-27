package com.jujin.freeway.cloud;

import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.cloud.context.CloudContextModule;
import com.jujin.freeway.cloud.discovery.CloudDiscoveryModule;
import com.jujin.freeway.cloud.discovery.LoadBalancer;
import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.cloud.health.CloudHealthModule;
import com.jujin.freeway.cloud.observe.CloudObserveModule;
import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.cloud.observe.Tracer;
import com.jujin.freeway.cloud.resilience.CloudResilienceModule;
import com.jujin.freeway.cloud.resilience.Retryer;
import com.jujin.freeway.cloud.resilience.RateLimiter;
import com.jujin.freeway.cloud.rpc.CloudHttpClient;
import com.jujin.freeway.cloud.rpc.CloudRpcModule;
import com.jujin.freeway.cloud.secret.CloudSecretModule;
import com.jujin.freeway.cloud.secret.SecretStore;
import com.jujin.freeway.cloud.storage.ObjectStorage;
import com.jujin.freeway.cloud.storage.CloudStorageModule;
import com.jujin.freeway.ioc.event.AsyncCarrier;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.symbol.KnownKeys;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.ModuleEx;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.jujin.freeway.cloud.CloudModule.ConfigKeys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CloudModule} is the cloud bundle: placing it places the eight
 * standard modules, and every module still installs on its own (subset
 * assembly) — taking a subset is placing the modules you want.
 */
class CloudModuleTest {

    private static final List<Class<?>> STANDARD = List.of(
        CloudContextModule.class,
        CloudSecretModule.class,
        CloudDiscoveryModule.class,
        CloudRpcModule.class,
        CloudObserveModule.class,
        CloudResilienceModule.class,
        CloudHealthModule.class,
        CloudStorageModule.class);

    @Test
    void cloudDeclaresEveryNamespaceItsKeyTableSpells() {
        // The vocabulary follows the table, not one literal: freeway.cloud.* and the app-name
        // fallback are both cloud's, and a key of the table outside the declared namespaces would
        // be refused at bind time (KnownKeys.of) — which is how this stayed a single table.
        try (Container container = Freeway.create(new CloudModule())) {   // the bundle owns the table
            Set<String> known = container.extension(KnownKeys.class).all().stream()
                .flatMap(vocabulary -> vocabulary.keys().stream())
                .collect(java.util.stream.Collectors.toSet());

            assertTrue(known.contains(ConfigKeys.EVENT_TOKEN), "freeway.cloud.* is declared");
            assertTrue(known.contains(ConfigKeys.APP_NAME), "freeway.app.* is declared too");
        }
    }

    @Test
    void bundleInstallsEveryStandardModule() {
        // Placing the bundle is placing its eight submodules — asserted through what each one makes
        // available, not through the tree the framework builds internally.
        try (Container container = Freeway.create(new CloudModule())) {
            assertNotNull(container.get(AsyncCarrier.class), "context: the async carrier is bound");
            assertNotNull(container.get(SecretStore.class), "secret");
            assertNotNull(container.get(ServiceRegistry.class), "discovery: registry");
            assertNotNull(container.get(ServiceDiscovery.class), "discovery: client");
            assertNotNull(container.get(CloudHttpClient.class), "rpc");
            assertNotNull(container.get(Tracer.class), "observe");
            assertNotNull(container.get(Retryer.class), "resilience");
            assertTrue(container.extension(Route.class).all().stream()
                    .map(Route::path)
                    .toList()
                    .containsAll(List.of("/health/live", "/health/ready")),
                "health: its two routes are contributed");
            assertNotNull(container.get(ObjectStorage.class), "storage");
        }
    }

    @Test
    void bundleSitsBesideApplicationModules() {
        // One ordered call of modules: the application's own module first, then the bundle.
        try (Container container = Freeway.create(new AppMarkerModule(), new CloudModule())) {
            assertNotNull(container.get(AppMarkerModule.Marker.class));
            assertNotNull(container.get(ServiceRegistry.class));
            assertNotNull(container.get(ServiceDiscovery.class));
            assertNotNull(container.get(CloudHttpClient.class));
            assertNotNull(container.get(SecretStore.class));
            assertNotNull(container.get(Tracer.class));
            assertNotNull(container.get(Metrics.class));

            // Marker-based selection: @Local marks every built-in default —
            // the single selector extension modules replace with a primary binding.
            assertNotNull(container.get(ServiceDiscovery.class, Local.class));
            assertNotNull(container.get(LoadBalancer.class, Local.class));
        }
    }

    @Test
    void aModuleCanBePlacedWithoutTheRestOfTheBundle() {
        // The subset form: taking the bundle apart is placing the individual module — one cloud
        // module, configured by the application.
        try (Container container = Freeway.create(CloudRpcModule.class)) {
            assertNotNull(container.get(CloudHttpClient.class));
            assertThrows(com.jujin.freeway.ioc.MissingBindingException.class,
                () -> container.get(SecretStore.class),
                "the rest of the bundle is absent because it was never placed");
        }
    }

    @Test
    void placingABundleAndASubmoduleOfItIsRefused() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> Freeway.create(CloudModule.class, CloudRpcModule.class));

        assertTrue(failure.getMessage().contains(CloudRpcModule.class.getName()),
            "the duplicate names the submodule and the fix (place the class once): "
                + failure.getMessage());
    }

    @Test
    void eachModuleInstallsIndependently() {
        for (Class<?> module : STANDARD) {
            ModuleEx instance = newModule(module);
            try (Container container = Freeway.create(instance)) {
                // Every module must be installable on its own (subset assembly).
            }
        }
    }

    private static ModuleEx newModule(Class<?> type) {
        try {
            return (ModuleEx) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot instantiate " + type.getName(), e);
        }
    }

    @Test
    void localMarkerDeclaresOnlyPositionsTheFrameworkReads() {
        // Same rule as ioc's annotation-target test: a position without a
        // reader is an affordance the framework does not keep. @Local is read
        // at injection points (through the marker index) and as a marker value
        // in .marker(...)/@Marker(...); ioc's MarkerIndex cannot read a cloud
        // annotation off a class, so TYPE would silently do nothing.
        assertEquals(Set.of(ElementType.FIELD, ElementType.PARAMETER),
            Set.of(Local.class.getAnnotation(Target.class).value()));
    }

    @Test
    void rateLimitingIsUnlimitedByDefault() {
        // rate-limit.enabled defaults to false; without any config the
        // resolved limiter must be the no-op singleton, not a 100 req/s
        // token bucket from the library fallback.
        try (Container container = Freeway.create(new CloudResilienceModule())) {
            RateLimiter limiter = container.get(RateLimiter.class);
            assertTrue(limiter.tryAcquire());
            assertTrue(limiter.tryAcquire());
        }
    }

    /** A tiny application module, to prove the bundle sits beside app modules. */
    static final class AppMarkerModule implements ModuleEx {

        record Marker() {}

        @Override
        public void bind(com.jujin.freeway.ioc.Binder binder) {
            binder.bind(Marker.class).to(c -> new Marker());
        }
    }
}
