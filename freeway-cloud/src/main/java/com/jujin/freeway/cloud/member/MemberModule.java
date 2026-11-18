package com.jujin.freeway.cloud.member;

import com.jujin.freeway.cloud.CloudHookIds;
import com.jujin.freeway.cloud.CloudKeys;
import com.jujin.freeway.cloud.discovery.ServiceDeclaration;
import com.jujin.freeway.cloud.health.CloudHealthContributor;
import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.symbol.KnownKeys;

/**
 * Service-membership lifecycle for any {@code ServiceRegistry} backend: collect
 * service declarations after the HTTP server starts, register them, renew,
 * publish readiness, and unregister during shutdown.
 *
 * <p>This module is deliberately backend-neutral. Install it with
 * {@code FleetModule} for dynamic Fleet discovery, with a third-party
 * adapter (for example Consul), or with a test fixture. Static discovery does not need it: fixed endpoints have no
 * membership lifecycle.</p>
 *
 * <p>The backend only supplies {@code ServiceRegistry}; readiness and drain
 * state belong to the membership lifecycle, not to the backend.</p>
 */
public final class MemberModule implements ModuleEx {

    @Override
    public void bind(Binder b) {
        b.contribute(KnownKeys.class)
            .add(KnownKeys.of(ConfigKeys.class, CloudKeys.PREFIX,
                CloudKeys.APP_PREFIX));
        b.bind(MemberState.class)
            .to(container -> new MemberState(container.get(Metrics.class)));

        b.contribute(ServiceDeclaration.class)
            .add("freeway.cloud.declaration.http", new HttpServiceDeclaration());
        b.contribute(CloudHealthContributor.class)
            .addDeferred(MemberReadiness.class);

        b.contribute(RuntimeHook.class)
            .add(CloudHookIds.DISCOVERY, new BackendGuardHook())
            .beforeIfPresent(CloudHookIds.HTTP_SERVER);
        b.contribute(RuntimeHook.class)
            .addDeferred(
                CloudHookIds.MEMBER,
                container -> new MemberHook(container.get(MemberState.class)))
            .afterIfPresent(CloudHookIds.HTTP_SERVER);
    }

    /** Membership/runtime keys; the backend-specific Fleet keys live with Fleet. */
    public static final class ConfigKeys {
        private ConfigKeys() {}

        public static final String DISCOVERY_TYPE = "freeway.cloud.discovery.type";
        public static final String REGISTRY_TYPE = "freeway.cloud.registry.type";

        public static final String MEMBER_SERVICE_ID = "freeway.cloud.member.service-id";
        public static final String MEMBER_SERVICE_HOST = "freeway.cloud.member.service-host";
        public static final String MEMBER_SERVICE_HOST_AUTO = "auto";
        public static final String MEMBER_SERVICE_HOST_DEFAULT = MEMBER_SERVICE_HOST_AUTO;
        public static final String MEMBER_SERVICE_SCHEME = "freeway.cloud.member.service-scheme";
        public static final String MEMBER_SERVICE_SCHEME_AUTO = "auto";
        public static final String MEMBER_SERVICE_SCHEME_DEFAULT = MEMBER_SERVICE_SCHEME_AUTO;
        public static final String MEMBER_SERVICE_PORT = "freeway.cloud.member.service-port";
        public static final String MEMBER_SERVICE_INSTANCE_ID =
            "freeway.cloud.member.service-instance-id";
        public static final String MEMBER_SHUTDOWN_DRAIN = "freeway.cloud.member.shutdown-drain";
        public static final String MEMBER_SHUTDOWN_DRAIN_AUTO = "auto";
        public static final java.time.Duration MEMBER_SHUTDOWN_DRAIN_DEFAULT =
            java.time.Duration.ZERO;
    }
}
