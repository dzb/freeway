package com.jujin.freeway.flow;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.extension.Extension;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.Scope;

/**
 * Flow 引擎模块 —— 注册到 freeway IoC 容器
 *
 * <pre>{@code
 * Freeway.create(new FlowModule(), ...);
 * // 或
 * container.get(FlowEngine.class).eval(graph, context);
 * }</pre>
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
            try {
                Class<?> clz = Class.forName(componentName);
                return fwContainer.get(clz);
            } catch (ClassNotFoundException e) {
                return null;
            }
        }
    }
}
