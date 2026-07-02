package com.jujin.freeway.flow;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.Scope;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;

/**
 * Flow 引擎的 Freeway IoC 适配模块。
 *
 * <p>安装此模块后，容器内：
 * <ul>
 *   <li>{@link FlowEngine} 作为单例绑定，自动装配 {@link FlowDriverDefault}
 *       和 {@link IocContainerAdapter}</li>
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
        binder.bind(FlowEngine.class)
                .to(container -> {
                    FlowEngine engine = FlowEngine.newInstance();
                    engine.register(new FlowDriverDefault(
                            new IocContainerAdapter(container),
                            null));
                    // Register contributed TaskComponents in the marker index
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
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}
