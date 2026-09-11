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

import java.util.List;


/**
 * Cloud umbrella module: aggregates the standard {@code freeway-cloud}
 * sub-modules. {@link com.jujin.freeway.cloud.event.CloudEventModule} is an
 * optional add-on and intentionally not installed here; add it explicitly when
 * the WebSocket event mesh is needed. Config files belong to the boot
 * framework (see {@code AppConfigDefault}) — the cloud module no longer reads
 * its own config file.
 *
 * <p>Install either this module <b>or</b> a subset of the sub-modules: the
 * container fails fast when the same module class reaches the tree twice
 * (this umbrella plus an explicit module), so double assembly is an immediate
 * startup error, never a silent double binding.
 */
@Marker(Builtin.class)
public final class CloudModule implements ModuleEx {

    /**
     * The standard cloud sub-modules, declared once when this module is
     * created and exposed as-is: {@link ModuleEx#subModules()} is a view of the
     * composition, not a factory the framework calls to assemble it. The
     * container resolves the tree before binding anything, so the composition
     * is visible up front rather than emerging from {@code bind()} call order.
     *
     * <p>{@link com.jujin.freeway.cloud.event.CloudEventModule} is intentionally
     * absent: add it explicitly when the WebSocket event mesh is needed.
     */
    private final List<ModuleEx> subModules = List.of(
        new CloudContextModule(),
        new CloudSecretModule(),
        new CloudDiscoveryModule(),
        new CloudRpcModule(),
        new CloudObserveModule(),
        new CloudResilienceModule(),
        new CloudHealthModule(),
        new CloudStorageModule());

    @Override
    public List<ModuleEx> subModules() {
        return subModules;
    }

    @Override
    public void bind(Binder b) {
        // The umbrella only composes; its sub-modules declare the bindings.
    }
}
