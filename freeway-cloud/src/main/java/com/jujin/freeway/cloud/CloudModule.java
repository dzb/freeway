package com.jujin.freeway.cloud;

import com.jujin.freeway.cloud.context.CloudContextModule;
import com.jujin.freeway.cloud.discovery.CloudDiscoveryModule;
import com.jujin.freeway.cloud.health.CloudHealthModule;
import com.jujin.freeway.cloud.observe.CloudObserveModule;
import com.jujin.freeway.cloud.resilience.CloudResilienceModule;
import com.jujin.freeway.cloud.rpc.CloudRpcModule;
import com.jujin.freeway.cloud.secret.CloudSecretModule;
import com.jujin.freeway.cloud.storage.CloudStorageModule;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.annotation.SubModule;

/**
 * The standard cloud bundle: the eight {@code freeway-cloud} modules declared
 * as one. Placing this module places them — pre-order, after the module
 * itself — and the startup tree shows the bundle as its own level:
 *
 * <pre>{@code
 * FreewayApp.run(ModuleNode.app("order-service",
 *     ModuleNode.leaf(new OrderModule()),
 *     ModuleNode.leaf(CloudModule.class)));   // the whole bundle
 * }</pre>
 *
 * <p>Any module below is an ordinary module, so taking a subset is placing it
 * directly instead of the bundle — no exclusion list exists:
 *
 * <pre>{@code
 * FreewayApp.run(ModuleNode.app("order-service",
 *     ModuleNode.leaf(CloudRpcModule.class)));   // just the client
 * }</pre>
 *
 * <p>{@code bind(Binder)} is the shared cloud surface: cross-cutting bindings
 * or contributions that belong to the bundle as a whole (the modules
 * themselves are declared by {@link SubModule}, not installed by this method).
 *
 * <p>{@link com.jujin.freeway.cloud.event.CloudEventModule} is intentionally
 * absent: it opens listeners and dials peers, so a mesh is something an
 * application asks for explicitly.
 */
@SubModule({
    CloudContextModule.class,
    CloudSecretModule.class,
    CloudDiscoveryModule.class,
    CloudRpcModule.class,
    CloudObserveModule.class,
    CloudResilienceModule.class,
    CloudHealthModule.class,
    CloudStorageModule.class
})
public final class CloudModule implements ModuleEx {

    @Override
    public void bind(Binder binder) {
        // Shared cloud bindings would live here. The standard modules arrive
        // through the @SubModule declaration above, not through this method:
        // composition is data, not a binding side effect.
    }
}
