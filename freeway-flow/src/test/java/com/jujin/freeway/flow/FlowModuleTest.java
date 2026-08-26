package com.jujin.freeway.flow;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.MissingBindingException;
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

    @Test
    void iocAdapterPropagatesAmbiguousBindingAsRealError() {
        // A genuine container failure (two services match the same type+id)
        // throws IllegalArgumentException with a non-"no service registered"
        // message — the adapter must surface it, not convert it to null.
        Container container = Freeway.create(binder -> {
            binder.bind(AmbiguousA.class).id("dup").to(AmbiguousA.class);
            binder.bind(AmbiguousB.class).id("dup").to(AmbiguousB.class);
        });
        try {
            FlowModule.IocContainerAdapter adapter = new FlowModule.IocContainerAdapter(container);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> adapter.getComponent("dup"));
            assertTrue(ex.getMessage().contains("Multiple services match type"),
                "got: " + ex.getMessage());
        } finally {
            container.close();
        }
    }

    @Test
    void iocAdapterPropagatesNonMissingIllegalArgumentFromMock() {
        // Any IllegalArgumentException that is NOT a MissingBindingException
        // is a real container failure and must surface, not become a null.
        Container container = mockContainer(null);
        FlowModule.IocContainerAdapter adapter = new FlowModule.IocContainerAdapter(container);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> adapter.getComponent("ambiguous"));
        assertTrue(ex.getMessage().contains("Multiple services match type"));
    }

    private static Container mockContainer(ConditionComponent condition) {
        return (Container) Proxy.newProxyInstance(
            Container.class.getClassLoader(),
            new Class<?>[] { Container.class },
            (proxy, method, args) -> {
                if ("get".equals(method.getName())
                        && args != null
                        && args.length == 2) {
                    Class<?> type = (Class<?>) args[0];
                    String id = (String) args[1];
                    if (type == TaskComponent.class && "boom".equals(id)) {
                        throw new RuntimeException("boom");
                    }
                    if (type == TaskComponent.class && "isReady".equals(id)) {
                        throw missingBinding(type, id);
                    }
                    if (type == ConditionComponent.class
                            && "isReady".equals(id)
                            && condition != null) {
                        return condition;
                    }
                    if ("missing".equals(id)) {
                        throw missingBinding(type, id);
                    }
                    if ("ambiguous".equals(id)) {
                        throw new IllegalArgumentException(
                            "Multiple services match type " + type.getName() + " and id " + id);
                    }
                }
                throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    /** Simulates the real container's missing-binding failure. */
    private static IllegalArgumentException missingBinding(Class<?> type, String id) {
        return new MissingBindingException(
            "No service registered for type " + type.getName() + " and id " + id);
    }

    static class AmbiguousA implements TaskComponent {
        @Override public void run(FlowContext ctx, Node node) { }
    }

    static class AmbiguousB implements TaskComponent {
        @Override public void run(FlowContext ctx, Node node) { }
    }
}
