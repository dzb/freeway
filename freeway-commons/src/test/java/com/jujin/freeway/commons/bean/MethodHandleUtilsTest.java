package com.jujin.freeway.commons.bean;

import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Cached method handles: plain virtual dispatch, and non-virtual
 * default-method dispatch for proxy receivers.
 */
class MethodHandleUtilsTest {

    interface Greeter {
        String greet(String name);

        default String fallback(String name) {
            return "default:" + name;
        }
    }

    static class LoudGreeter implements Greeter {
        @Override
        public String greet(String name) {
            return "loud:" + name;
        }
    }

    @Test
    void methodHandleInvokesWithPositionalArgsAndIsCached() throws Throwable {
        Method greet = Greeter.class.getMethod("greet", String.class);
        MethodHandle handle = MethodHandleUtils.methodHandle(greet);

        assertEquals("loud:ada",
            MethodHandleUtils.invokeOn(handle, new LoudGreeter(), new Object[]{"ada"}));
        assertSame(handle, MethodHandleUtils.methodHandle(greet),
            "handles are cached per method");
    }

    @Test
    void defaultHandleDispatchesNonVirtuallyPastAProxy() throws Throwable {
        // A JDK proxy overrides every interface method, so the virtual handle
        // from methodHandle() re-enters the proxy handler; defaultMethodHandle
        // must reach the default body instead.
        Method fallback = Greeter.class.getMethod("fallback", String.class);
        Greeter proxy = (Greeter) Proxy.newProxyInstance(
            Greeter.class.getClassLoader(), new Class<?>[]{Greeter.class},
            (p, method, args) -> "PROXY:" + method.getName());

        assertEquals("PROXY:fallback",
            MethodHandleUtils.invokeOn(
                MethodHandleUtils.methodHandle(fallback), proxy, new Object[]{"ada"}),
            "virtual dispatch on a proxy hits the proxy handler");
        assertEquals("default:ada",
            MethodHandleUtils.invokeOn(
                MethodHandleUtils.defaultMethodHandle(fallback), proxy, new Object[]{"ada"}),
            "the default handle bypasses the proxy into the default body");
        assertSame(MethodHandleUtils.defaultMethodHandle(fallback),
            MethodHandleUtils.defaultMethodHandle(fallback),
            "default handles are cached per method");
    }

    @Test
    void defaultHandleRejectsNonDefaultMethods() throws Exception {
        Method greet = Greeter.class.getMethod("greet", String.class);
        assertThrows(IllegalArgumentException.class,
            () -> MethodHandleUtils.defaultMethodHandle(greet));
    }
}
