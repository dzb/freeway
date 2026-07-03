package com.jujin.freeway.flow;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.Scope;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;

import java.util.Map;

/**
 * Flow 引擎的 Freeway IoC 适配模块。
 *
 * <p>安装此模块后，容器内：
 * <ul>
 *   <li>{@link FlowDriverDefault} 以 id {@code "default"} 贡献到
 *       {@link FlowDriver} 扩展点</li>
 *   <li>用户可通过 {@code binder.contribute(FlowDriver.class).add("custom", myDriver)}
 *       或 {@code .add(MyDriver.class)}（自动调用 {@code container.create()} 注入）
 *       注册自定义驱动器，图定义中 {@code "driver":"custom"} 即使用之</li>
 *   <li>{@link FlowEngine} 作为单例绑定，driver map 由模块从
 *       {@code Extension<FlowDriver>} 组装后传入，引擎本体不感知 IoC</li>
 *   <li>通过 {@code binder.contribute(TaskComponent.class)} 贡献的
 *       handler 自动注册到引擎的 {@link FlowMarkerIndex}，
 *       可通过 {@code !markerName} 引用</li>
 *   <li>{@code @beanName} 引用走 {@code container.get(TaskComponent.class, name)}
 *       ——适用于显式绑定了 id 的场景</li>
 * </ul>
 */
@Marker(Builtin.class)
public class FlowModule implements ModuleEx {

    @Override
    public void bind(Binder binder) {
        var adapter = new IocContainerAdapter(null); // completed on engine creation

        // Contribute the default driver
        binder.contribute(FlowDriver.class)
            .add("default", new FlowDriverDefault(adapter, null));

        // Bind the engine — builds driver map from Extension, passes plain Map to engine
        binder.bind(FlowEngine.class)
                .to(container -> {
                    adapter.fwContainer = container; // complete the adapter
                    Map<String, FlowDriver> driverMap = container.extension(FlowDriver.class).asMap();
                    FlowEngine engine = FlowEngine.newInstance(driverMap);
                    for (var handler : container.extension(TaskComponent.class).all()) {
                        engine.register(handler);
                    }
                    return engine;
                })
                .scope(Scope.SINGLETON);
    }

    static class IocContainerAdapter implements FlowContainer {
        volatile Container fwContainer;

        IocContainerAdapter(Container fwContainer) {
            this.fwContainer = fwContainer;
        }

        @Override
        public Object getComponent(String componentName) {
            try {
                return fwContainer.get(TaskComponent.class, componentName);
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}
