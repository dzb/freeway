package com.jujin.freeway.flow;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.extension.Extension;
import com.jujin.freeway.ioc.Module2;
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
public class FlowModule implements Module2 {

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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void registerTypedTasks(FlowEngine engine, Container container) {
        try {
            Extension ext = container.get(
                    Extension.class, TaskComponent.class.getName());
            for (Object entry : ext.all()) {
                if (entry instanceof TaskComponent handler) {
                    // 优先从容器获取（触发 @Inject 注入），否则直接用原始实例
                    TaskComponent resolved = resolveTaskComponent(container, handler);
                    engine.register(resolved.getClass(), resolved);
                }
            }
        } catch (Exception ignored) {
            // no typed tasks contributed
        }
    }

    @SuppressWarnings("unchecked")
    private static TaskComponent resolveTaskComponent(Container container, TaskComponent handler) {
        try {
            // If the handler class is registered as a service, get the
            // container-managed instance which triggers @Inject injection.
            return container.get((Class<TaskComponent>) handler.getClass());
        } catch (Exception e) {
            // Not registered — use the raw contributed instance.
            return handler;
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
