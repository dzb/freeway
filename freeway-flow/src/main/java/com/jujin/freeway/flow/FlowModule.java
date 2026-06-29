package com.jujin.freeway.flow;

import com.jujin.freeway.ioc.Binder;
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
                    return engine;
                })
                .scope(Scope.SINGLETON);
    }

    /**
     * 将 freeway IoC Container 适配为 flow 的 FlowContainer 接口
     */
    static class IocContainerAdapter implements FlowContainer {
        private final com.jujin.freeway.ioc.Container fwContainer;

        IocContainerAdapter(com.jujin.freeway.ioc.Container fwContainer) {
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
