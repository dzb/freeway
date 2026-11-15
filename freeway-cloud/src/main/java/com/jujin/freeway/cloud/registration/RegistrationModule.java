package com.jujin.freeway.cloud.registration;

import com.jujin.freeway.cloud.CloudKeys;
import com.jujin.freeway.cloud.CloudHookIds;
import com.jujin.freeway.ioc.symbol.KnownKeys;
import com.jujin.freeway.cloud.discovery.ServiceDeclaration;
import com.jujin.freeway.cloud.health.CloudHealthContributor;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;

/**
 * Registration lifecycle for any {@code ServiceRegistry} backend: collect
 * service declarations after the HTTP server starts, register them, renew,
 * publish readiness, and unregister during shutdown.
 *
 * <p>This module is deliberately backend-neutral. Install it with
 * {@code FleetModule} for dynamic discovery, with
 * {@code FleetModule} for a Fleet backend, or with a third-party
 * adapter. Static discovery does not need it: fixed endpoints have no
 * registration lifecycle.</p>
 *
 * <p>The backend only supplies {@code ServiceRegistry}; readiness and drain
 * state belong to the registration lifecycle, not to the backend.</p>
 */
public final class RegistrationModule implements ModuleEx {

    @Override
    public void bind(Binder b) {
        b.contribute(KnownKeys.class)
            .add(KnownKeys.of(ConfigKeys.class, CloudKeys.PREFIX,
                CloudKeys.APP_PREFIX));
        Renewal renewal = new Renewal();
        b.bind(Renewal.class).to(container -> renewal);

        b.contribute(ServiceDeclaration.class)
            .add("freeway.cloud.declaration.http", new HttpDeclaration());
        b.contribute(CloudHealthContributor.class)
            .addDeferred(RegistrationHealth.class);

        b.contribute(RuntimeHook.class)
            .add(CloudHookIds.DISCOVERY, new TypeGuardHook())
            .beforeIfPresent(CloudHookIds.HTTP_SERVER);
        b.contribute(RuntimeHook.class)
            .add(CloudHookIds.REGISTRY, new RegistrationHook(renewal))
            .afterIfPresent(CloudHookIds.HTTP_SERVER);
    }

    /** Registration/runtime keys; the backend-specific Fleet keys live with Fleet. */
    public static final class ConfigKeys {
        private ConfigKeys() {}

        public static final String DISCOVERY_TYPE = "freeway.cloud.discovery.type";
        public static final String REGISTRY_TYPE = "freeway.cloud.registry.type";

        public static final String REGISTRY_SERVICE_ID = "freeway.cloud.registry.service-id";
        public static final String REGISTRY_SERVICE_HOST = "freeway.cloud.registry.service-host";
        public static final String REGISTRY_SERVICE_HOST_AUTO = "auto";
        public static final String REGISTRY_SERVICE_HOST_DEFAULT = REGISTRY_SERVICE_HOST_AUTO;
        public static final String REGISTRY_SERVICE_SCHEME = "freeway.cloud.registry.service-scheme";
        public static final String REGISTRY_SERVICE_SCHEME_AUTO = "auto";
        public static final String REGISTRY_SERVICE_SCHEME_DEFAULT = REGISTRY_SERVICE_SCHEME_AUTO;
        public static final String REGISTRY_SERVICE_PORT = "freeway.cloud.registry.service-port";
        public static final String REGISTRY_SERVICE_INSTANCE_ID =
            "freeway.cloud.registry.service-instance-id";
        public static final String REGISTRY_SHUTDOWN_DRAIN = "freeway.cloud.registry.shutdown-drain";
        public static final String REGISTRY_SHUTDOWN_DRAIN_AUTO = "auto";
        public static final java.time.Duration REGISTRY_SHUTDOWN_DRAIN_DEFAULT =
            java.time.Duration.ZERO;
    }
}
