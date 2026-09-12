package com.jujin.freeway.cloud;

import com.jujin.freeway.cloud.context.CloudContextModule;
import com.jujin.freeway.cloud.discovery.CloudDiscoveryModule;
import com.jujin.freeway.cloud.health.CloudHealthModule;
import com.jujin.freeway.cloud.observe.CloudObserveModule;
import com.jujin.freeway.cloud.resilience.CloudResilienceModule;
import com.jujin.freeway.cloud.rpc.CloudRpcModule;
import com.jujin.freeway.cloud.secret.CloudSecretModule;
import com.jujin.freeway.cloud.storage.CloudStorageModule;
import com.jujin.freeway.ioc.ModuleNode;

/**
 * The cloud bundle: the standard {@code freeway-cloud} modules as one
 * composition fragment.
 *
 * <pre>{@code
 * FreewayApp.run(ModuleNode.app("order-service",
 *     ModuleNode.leaf(new OrderModule()),
 *     CloudModules.standard()));
 * }</pre>
 *
 * <p>The bundle used to be a module ({@code CloudModule}) that declared its
 * children, which meant an application could not replace or leave out one of
 * them: two instances of a module class are refused, and a module owns its
 * sub-modules. A fragment is a plain value instead — place it, nest it under a
 * group, take it apart, or leave it out and compose the eight modules yourself:
 *
 * <pre>{@code
 * ModuleNode.of(new CloudRpcModule())      // just this one, configured my way
 * }</pre>
 *
 * <p>{@link com.jujin.freeway.cloud.event.CloudEventModule} is intentionally
 * absent: it opens listeners and dials peers, so a mesh is something an
 * application asks for explicitly.
 */
public final class CloudModules {

    private CloudModules() {
    }

    /**
     * A fresh fragment holding the standard cloud modules. Each call builds new
     * module instances — a fragment is a value, and one instance belongs to one
     * place in one tree.
     */
    public static ModuleNode standard() {
        return ModuleNode.group("freeway-cloud",
            ModuleNode.leaf(new CloudContextModule()),
            ModuleNode.leaf(new CloudSecretModule()),
            ModuleNode.leaf(new CloudDiscoveryModule()),
            ModuleNode.leaf(new CloudRpcModule()),
            ModuleNode.leaf(new CloudObserveModule()),
            ModuleNode.leaf(new CloudResilienceModule()),
            ModuleNode.leaf(new CloudHealthModule()),
            ModuleNode.leaf(new CloudStorageModule()));
    }
}
