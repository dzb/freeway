package com.jujin.freeway.flow;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.Scope;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Freeway IoC adapter for the Flow engine: assembles the engine from
 * container contributions, then the engine itself runs IoC-free.
 *
 * <ul>
 *   <li>{@link FlowEngine} is a singleton; its driver map is built from the
 *       contributed {@link FlowDriver}s over a built-in {@code "default"}
 *       that resolves against this container</li>
 *   <li>graph tasks and conditions reference container components as
 *       {@code @name} — resolved through {@code container.get(type, name)},
 *       so any {@link TaskComponent}/{@link ConditionComponent} bound or
 *       contributed with an explicit id is reachable</li>
 *   <li>contributed {@link FlowInterceptor}s form the engine's interceptor
 *       chain in extension order — the chain is fixed at load; nothing can
 *       add or remove an interceptor while runs are in flight</li>
 *   <li>a contributed driver with id {@code "default"} overrides the built-in
 *       (the usual {@code .primary()} seam)</li>
 * </ul>
 */
@Marker(Builtin.class)
public class FlowModule implements ModuleEx {

    private static final Logger LOG = LoggerFactory.getLogger(FlowModule.class);

    @Override
    public void bind(Binder binder) {
        binder.bind(FlowEngine.class)
            .to(container -> {
                Map<String, FlowDriver> driverMap = new HashMap<>();
                driverMap.put("default", new FlowDriverDefault(container, null));
                Map<String, FlowDriver> contributed =
                    container.extension(FlowDriver.class).asMap();
                if (contributed.containsKey("default")) {
                    LOG.warn("Contributed driver with id 'default' overrides the built-in FlowDriverDefault");
                }
                driverMap.putAll(contributed);
                List<FlowInterceptor> interceptors =
                    container.extension(FlowInterceptor.class).all();
                return FlowEngine.create(driverMap, interceptors);
            })
            .scope(Scope.SINGLETON);
    }
}
