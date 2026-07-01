package com.jujin.freeway.flow;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.extension.Extension;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.Scope;

/**
 * Flow 引擎的 Freeway IoC 适配模块。
 *
 * <p>迁移说明：
 * <ul>
 *   <li>把 FlowEngine 作为 Freeway 的显式绑定项注册到 {@link Container}，替代 Solon 风格的组件发现。</li>
 *   <li>通过 {@link IocContainerAdapter} 把 Freeway 容器映射到 flow 侧的组件解析接口。</li>
 *   <li>保留 {@link TaskComponent} 的类型化注册，方便把既有任务处理器直接挂到迁移后的引擎上。</li>
 * </ul>
 * 这样改是为了让 flow-engine 作为移植模块在 Freeway 内可直接安装、可直接使用。</p>
 */
public class FlowModule implements ModuleEx {

    @Override
    public void bind(Binder binder) {
        binder.bind(FlowEngine.class)
                .to(container -> {
                    FlowEngine engine = FlowEngine.newInstance();
                    engine.register(new FlowDriverDefault(
                            new IocContainerAdapter(container),
                            null));
                    registerTypedTasks(engine, container);
                    return engine;
                })
                .scope(Scope.SINGLETON);
    }

    private void registerTypedTasks(FlowEngine engine, Container container) {
        var ext = container.extension(TaskComponent.class);
        for (var handler : ext.all()) {
            engine.register(handler.getClass(), handler);
        }
    }

    static class IocContainerAdapter implements FlowContainer {
        private final Container fwContainer;

        IocContainerAdapter(Container fwContainer) {
            this.fwContainer = fwContainer;
        }

        @Override
        public Object getComponent(String componentName) {
            // try FQCN first
            try {
                Class<?> clz = Class.forName(componentName);
                return fwContainer.get(clz);
            } catch (ClassNotFoundException e) {
                // not a FQCN — fall through
            }

            // try by name/id through the container's extension registry
            try {
                var ext = fwContainer.extension(TaskComponent.class);
                for (var handler : ext.all()) {
                    if (componentName.equals(handler.getClass().getSimpleName())) {
                        return handler;
                    }
                }
            } catch (Exception ignored) {
                // extension lookup is best-effort
            }

            return null;
        }
    }
}
