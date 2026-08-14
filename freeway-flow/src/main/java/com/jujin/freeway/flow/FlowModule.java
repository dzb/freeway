package com.jujin.freeway.flow;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.Scope;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Freeway IoC adapter module for the Flow engine.
 *
 * <p>After installing this module, inside the container:
 * <ul>
 *   <li>{@link FlowContainer} is bound as a singleton, used by {@link FlowDriverDefault}
 *       to resolve {@code @beanName} references</li>
 *   <li>{@link FlowDriverDefault} is assembled automatically when the engine is created,
 *       injected into the driver map with id {@code "default"}</li>
 *   <li>Users can register custom drivers via {@code binder.contribute(FlowDriver.class).add("custom", myDriver)}
 *       or {@code .add(MyDriver.class)} (which automatically calls {@code container.create()} to inject),
 *       and {@code "driver":"custom"} in a graph definition then uses it</li>
 *   <li>{@link FlowEngine} is bound as a singleton; the driver map is assembled by the module and passed in,
 *       and the engine itself is unaware of IoC</li>
 *   <li>Handlers contributed via {@code binder.contribute(TaskComponent.class)} are
 *       automatically registered in the engine's {@link FlowMarkerIndex} and can be
 *       referenced with {@code !markerName}</li>
 *   <li>{@code @beanName} references go through {@code container.get(TaskComponent.class, name)}
 *       — suitable for components explicitly bound with an id</li>
 * </ul>
 */
@Marker(Builtin.class)
public class FlowModule implements ModuleEx {

    private static final Logger LOG = LoggerFactory.getLogger(FlowModule.class);

    @Override
    public void bind(Binder binder) {
        // FlowContainer adapter — used by FlowDriverDefault for @beanName resolution
        binder.bind(FlowContainer.class)
            .to((Container c) -> new IocContainerAdapter(c))
            .scope(Scope.SINGLETON);

        // Bind the engine — builds driver map from contributions + default,
        // passes plain Map to engine to keep it IoC-free
        binder.bind(FlowEngine.class)
                .to(container -> {
                    Map<String, FlowDriver> driverMap = new HashMap<>();
                    // Default driver — gets FlowContainer from the container
                    driverMap.put("default", new FlowDriverDefault(
                        container.get(FlowContainer.class), null));
                    // Custom drivers from contribute
                    var contributed = container.extension(FlowDriver.class).asMap();
                    if (contributed.containsKey("default")) {
                        LOG.warn("Contributed driver with id 'default' overrides the built-in FlowDriverDefault");
                    }
                    driverMap.putAll(contributed);

                    FlowEngine engine = FlowEngine.newInstance(driverMap);
                    for (var handler : container.extension(TaskComponent.class).all()) {
                        engine.register(handler);
                    }
                    return engine;
                })
                .scope(Scope.SINGLETON);
    }

    static class IocContainerAdapter implements FlowContainer {
        private final Container fwContainer;

        IocContainerAdapter(Container fwContainer) {
            this.fwContainer = fwContainer;
        }

        @Override
        public Object getComponent(String componentName) {
            // TaskComponent first (most common), then ConditionComponent —
            // a @beanName condition reference must resolve components bound
            // as ConditionComponent, not only TaskComponent.
            try {
                return fwContainer.get(TaskComponent.class, componentName);
            } catch (IllegalArgumentException e) {
                if (!isMissingBinding(e)) throw e;
                LOG.debug("Failed to resolve @beanName '{}' as TaskComponent: no binding", componentName);
            }
            try {
                return fwContainer.get(ConditionComponent.class, componentName);
            } catch (IllegalArgumentException e) {
                if (!isMissingBinding(e)) throw e;
                LOG.debug("Failed to resolve @beanName '{}' as ConditionComponent: no binding", componentName);
                return null;
            }
        }

        /**
         * The container's {@code get(type, id)} throws an
         * {@link IllegalArgumentException} with a "No service registered for
         * type ..." message only when no binding matches. That is the single
         * IAE that means "component does not exist" — every other IAE
         * (multiple services match, advisor unsupported, scope/config or
         * lifecycle validation errors) is a real container failure and must
         * propagate instead of being masked as a missing component.
         */
        private static boolean isMissingBinding(IllegalArgumentException e) {
            return e.getMessage() != null
                && e.getMessage().startsWith("No service registered for type ");
        }
    }

}
