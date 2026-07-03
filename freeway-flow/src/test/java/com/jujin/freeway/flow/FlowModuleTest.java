package com.jujin.freeway.flow;

import com.jujin.freeway.ioc.Container;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

class FlowModuleTest {

    @Test
    void iocAdapterReturnsNullForMissingBeanButPropagatesRealFailures() {
        Container container = (Container) Proxy.newProxyInstance(
            Container.class.getClassLoader(),
            new Class<?>[] { Container.class },
            (proxy, method, args) -> {
                if ("get".equals(method.getName())
                        && args != null
                        && args.length == 2
                        && args[0] == TaskComponent.class
                        && "missing".equals(args[1])) {
                    throw new IllegalArgumentException("No binding");
                }
                if ("get".equals(method.getName())
                        && args != null
                        && args.length == 2
                        && args[0] == TaskComponent.class
                        && "boom".equals(args[1])) {
                    throw new RuntimeException("boom");
                }
                throw new UnsupportedOperationException(method.getName());
            }
        );

        FlowModule.IocContainerAdapter adapter = new FlowModule.IocContainerAdapter(container);
        assertNull(adapter.getComponent("missing"));
        assertThrows(RuntimeException.class, () -> adapter.getComponent("boom"));
    }
}
