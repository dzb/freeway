package com.jujin.freeway.flow;

import com.jujin.freeway.ioc.Container;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowModuleTest {

    @Test
    void iocAdapterReturnsNullForMissingBeanButPropagatesRealFailures() {
        Container container = mockContainer(null);
        FlowModule.IocContainerAdapter adapter = new FlowModule.IocContainerAdapter(container);
        assertNull(adapter.getComponent("missing"));
        assertThrows(RuntimeException.class, () -> adapter.getComponent("boom"));
    }

    @Test
    void iocAdapterResolvesConditionComponent() {
        // A @beanName condition reference must resolve components bound as
        // ConditionComponent — the adapter falls back after a TaskComponent miss.
        ConditionComponent condition = ctx -> true;
        Container container = mockContainer(condition);
        FlowModule.IocContainerAdapter adapter = new FlowModule.IocContainerAdapter(container);

        Object component = adapter.getComponent("isReady");
        assertTrue(component instanceof ConditionComponent);
        assertSame(condition, component);
    }

    private static Container mockContainer(ConditionComponent condition) {
        return (Container) Proxy.newProxyInstance(
            Container.class.getClassLoader(),
            new Class<?>[] { Container.class },
            (proxy, method, args) -> {
                if ("get".equals(method.getName())
                        && args != null
                        && args.length == 2) {
                    if (args[0] == TaskComponent.class
                            && "boom".equals(args[1])) {
                        throw new RuntimeException("boom");
                    }
                    if (args[0] == TaskComponent.class
                            && "isReady".equals(args[1])) {
                        throw new IllegalArgumentException("No TaskComponent binding");
                    }
                    if (args[0] == ConditionComponent.class
                            && "isReady".equals(args[1])
                            && condition != null) {
                        return condition;
                    }
                    if ("missing".equals(args[1])) {
                        throw new IllegalArgumentException("No binding");
                    }
                }
                throw new UnsupportedOperationException(method.getName());
            }
        );
    }
}
