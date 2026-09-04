package com.jujin.freeway.cloud.storage;

import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.CloudHooks;
import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.cloud.internal.BackendTypeGuard;
import com.jujin.freeway.cloud.storage.ObjectStorageDefault;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;
import com.jujin.freeway.ioc.symbol.SymbolSource;

import java.nio.file.Path;

/**
 * IoC wiring for the optional object storage subsystem: {@link ObjectStorage}
 * → {@link ObjectStorageDefault} (local file system, {@code @Local} marker),
 * rooted at {@code freeway.cloud.storage.base-path}
 * (default {@code cloud-storage} in the working directory). Put/delete emit
 * domain events on the {@link EventBus}. Decoupled from the core
 * discovery/rpc/config/observe/resilience chain.
 */
@Marker(Builtin.class)
public final class CloudStorageModule implements ModuleEx {

    @Override
    public void bind(Binder b) {
        b.bind(ObjectStorage.class)
            .to((Container container) -> {
                String basePath = container.get(SymbolSource.class)
                    .resolve(CloudConfigKeys.STORAGE_BASE_PATH, "cloud-storage");
                return new ObjectStorageDefault(
                    Path.of(basePath),
                    event -> container.get(EventBus.class).publish(event));
            })
            .marker(Local.class)
            ;

        b.contribute(RuntimeHook.class)
            .add(CloudHooks.STORAGE, new RuntimeHook() {
                @Override
                public void start(Container container) {
                    BackendTypeGuard.warnIfExternal(
                        container, ObjectStorage.class,
                        CloudConfigKeys.STORAGE_TYPE, "storage");
                }
            });
    }
}
