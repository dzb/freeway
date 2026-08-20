package com.jujin.freeway.cloud.config;

import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.cloud.internal.CloudConfigDefault;
import com.jujin.freeway.cloud.internal.CloudConfigSymbolProvider;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;
import com.jujin.freeway.ioc.symbol.SymbolProvider;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * IoC wiring for the dynamic config subsystem.
 *
 * <ul>
 *   <li>{@link CloudConfig} → {@link CloudConfigDefault} (WatchService file hot
 *       reload, {@code @Local} + {@code }); change notifications are
 *       published as {@link ConfigChangedEvent} on the {@link EventBus}.</li>
 *   <li>Dynamic {@link SymbolProvider} contribution — {@code @Value}/
 *       {@code @Symbol} resolution reads the latest config value. Registered as
 *       a class contribution (on-demand facade) and the file path comes from a
 *       system property, so provider lookup never recurses into symbol
 *       resolution.</li>
 *   <li>{@code freeway.cloud.config} {@link RuntimeHook} — stops the watch
 *       thread on shutdown (before the HTTP server, per the lifecycle
 *       topology).</li>
 * </ul>
 */
@Marker(Builtin.class)
public final class CloudConfigModule implements ModuleEx {

    private final AtomicReference<CloudConfigDefault> runtime = new AtomicReference<>();

    @Override
    public void bind(Binder b) {
        b.bind(CloudConfig.class)
            .to((Container container) -> {
                CloudConfigDefault impl = new CloudConfigDefault(configFile(),
                    event -> container.get(EventBus.class).publish(event));
                runtime.set(impl);
                return impl;
            })
            .marker(Local.class)
            ;

        b.contribute(SymbolProvider.class).add(CloudConfigSymbolProvider.class);

        b.contribute(RuntimeHook.class)
            .add("freeway.cloud.config", new RuntimeHook() {
                @Override
                public void start(Container container) {
                    // No persistent connection for the file backend.
                }

                @Override
                public void stop(Container container) {
                    CloudConfigDefault impl = runtime.get();
                    if (impl != null) {
                        impl.close(); // stop the WatchService thread
                    }
                }
            })
            .before("freeway.http.server");
    }

    /**
     * Config file path from {@code freeway.cloud.config.file} (system property)
     * or the working-directory default. Deliberately NOT resolved through
     * {@code SymbolSource}: the config provider itself participates in symbol
     * resolution, so routing the path through it would recurse.
     */
    private static Path configFile() {
        String path = System.getProperty(CloudConfigKeys.CONFIG_FILE);
        return Path.of(path == null ? "application-cloud.properties" : path);
    }
}
