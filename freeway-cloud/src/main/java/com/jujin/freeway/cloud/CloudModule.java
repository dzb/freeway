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
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;

/**
 * Cloud umbrella module: aggregates the standard {@code freeway-cloud}
 * sub-modules. {@link com.jujin.freeway.cloud.events.CloudEventModule} is an
 * optional add-on and intentionally not installed here; add it explicitly when
 * the WebSocket event mesh is needed. Config files belong to the boot
 * framework (see {@code AppConfigDefault}) — the cloud module no longer reads
 * its own config file.
 *
 * <p>Install either this module <b>or</b> a subset of the sub-modules: the
 * container fails fast when the same module class is installed twice
 * (explicit install plus this umbrella both counting), so double assembly
 * is an immediate startup error, never a silent double binding.
 */
@Marker(Builtin.class)
public final class CloudModule implements ModuleEx {

    @Override
    public void bind(Binder b) {
        b.install(new CloudContextModule());
        b.install(new CloudSecretModule());
        b.install(new CloudDiscoveryModule());
        b.install(new CloudRpcModule());
        b.install(new CloudObserveModule());
        b.install(new CloudResilienceModule());
        b.install(new CloudHealthModule());
        b.install(new CloudStorageModule());
    }
}
