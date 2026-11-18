package com.jujin.freeway.cloud;

import com.jujin.freeway.cloud.context.ContextModule;
import com.jujin.freeway.cloud.health.HealthModule;
import com.jujin.freeway.cloud.observe.ObserveModule;
import com.jujin.freeway.cloud.resilience.ResilienceModule;
import com.jujin.freeway.cloud.rpc.RpcModule;
import com.jujin.freeway.cloud.secret.SecretModule;
import com.jujin.freeway.cloud.storage.StorageModule;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.annotation.SubModule;
import com.jujin.freeway.ioc.symbol.KnownKeys;

/**
 * The standard cloud bundle: the seven runtime modules declared as one.
 * Placing this module places them — pre-order, after the module itself.
 *
 * <pre>{@code
 * FreewayApp.create(new OrderModule()).add(CloudModule.class).start();
 * }</pre>
 *
 * <p>Any submodule is an ordinary module, so taking a subset means placing the
 * module directly instead of the bundle. A subset still has to include what it
 * reads: RPC resolves through {@code ServiceDiscovery} and {@code LoadBalancer},
 * which the bundle does not bind by default. A client-only application places
 * {@code RpcModule} plus {@code StaticModule}, or a Fleet/Consul adapter.
 *
 * <p>{@code EventMeshModule} is intentionally absent: it opens listeners and
 * dials peers, so a mesh is something an application asks for explicitly.
 * Discovery implementations are also absent: membership lives in
 * {@code MemberModule}, backends in {@code freeway-cloud-fleet} or ext.
 */
@SubModule({
    ContextModule.class,
    SecretModule.class,
    RpcModule.class,
    ObserveModule.class,
    ResilienceModule.class,
    HealthModule.class,
    StorageModule.class
})
public final class CloudModule implements ModuleEx {

    @Override
    public void bind(Binder binder) {
        // This table owns only the application identity keys; each feature
        // module contributes its own vocabulary.
        binder.contribute(KnownKeys.class).add(
            KnownKeys.of(ConfigKeys.class, CloudKeys.PREFIX, CloudKeys.APP_PREFIX));
    }

    /**
     * Application identity keys shared across cloud features. Feature keys live
     * with the module that reads them (for example
     * {@code RpcModule.ConfigKeys}, {@code MemberModule.ConfigKeys}).
     */
    public static final class ConfigKeys {
        private ConfigKeys() {}

        /** Service-name fallback used by membership and the event mesh. */
        public static final String APP_NAME = "freeway.app.name";
        /** Value used when no member service-id and no app name are set. */
        public static final String APP_NAME_DEFAULT = "freeway-app";
    }
}
