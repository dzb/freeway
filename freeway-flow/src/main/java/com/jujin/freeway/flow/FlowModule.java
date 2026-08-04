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
 * Flow 引擎的 Freeway IoC 适配模块。
 *
 * <p>安装此模块后，容器内：
 * <ul>
 *   <li>{@link FlowContainer} 绑定为单例，供 {@link FlowDriverDefault}
 *       解析 {@code @beanName} 引用</li>
 *   <li>{@link FlowDriverDefault} 在引擎创建时自动组装，
 *       以 id {@code "default"} 注入 driver map</li>
 *   <li>用户可通过 {@code binder.contribute(FlowDriver.class).add("custom", myDriver)}
 *       或 {@code .add(MyDriver.class)}（自动调用 {@code container.create()} 注入）
 *       注册自定义驱动器，图定义中 {@code "driver":"custom"} 即使用之</li>
 *   <li>{@link FlowEngine} 作为单例绑定，driver map 由模块组装后传入，
 *       引擎本体不感知 IoC</li>
 *   <li>通过 {@code binder.contribute(TaskComponent.class)} 贡献的
 *       handler 自动注册到引擎的 {@link FlowMarkerIndex}，
 *       可通过 {@code !markerName} 引用</li>
 *   <li>{@code @beanName} 引用走 {@code container.get(TaskComponent.class, name)}
 *       ——适用于显式绑定了 id 的场景</li>
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
            try {
                return fwContainer.get(TaskComponent.class, componentName);
            } catch (IllegalArgumentException e) {
                LOG.debug("Failed to resolve @beanName '{}'", componentName, e);
                return null;
            }
        }
    }

}
