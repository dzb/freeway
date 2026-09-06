package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.commons.bean.MethodHandleUtils;
import com.jujin.freeway.ioc.CallBus;
import com.jujin.freeway.ioc.DeadCallException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;

/**
 * Internal typed consumer proxy factory for {@link CallBus}.
 */
public final class CallProxyFactory {

    public static final CallProxyFactory INSTANCE = new CallProxyFactory();

    private CallProxyFactory() {
    }

    public <T> T create(CallBus bus, String topicMapping, Class<T> api) {
        Objects.requireNonNull(bus, "bus");
        Objects.requireNonNull(topicMapping, "topicMapping");
        Objects.requireNonNull(api, "api");
        if (!api.isInterface()) {
            throw new IllegalArgumentException(
                "CallBus can only proxy interfaces: " + api.getName());
        }
        return api.cast(Proxy.newProxyInstance(
            api.getClassLoader(),
            new Class<?>[]{api},
            (proxy, method, args) -> invoke(bus, topicMapping, api, proxy, method, args)
        ));
    }

    private Object invoke(
        CallBus bus,
        String topicMapping,
        Class<?> api,
        Object proxy,
        Method method,
        Object[] args
    ) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return handleObjectMethod(proxy, method, args, topicMapping, api);
        }
        try {
            // Null-tolerant view (not List.of): interface methods legitimately
            // take null arguments, and the JDK hands them over as null array
            // elements — List.of would fail the call at the proxy instead of
            // dispatching it. Mirrors RemoteProxyFactory.asList.
            return bus.call(methodTopic(topicMapping, method.getName()),
                    args == null ? List.of() : Arrays.asList(args))
                .toCompletableFuture()
                .join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof DeadCallException && method.isDefault()) {
                return invokeDefault(proxy, method, args);
            }
            throw cause;
        }
    }

    private static String methodTopic(String topicMapping, String methodName) {
        return topicMapping + "." + methodName;
    }

    private static Object invokeDefault(Object proxy, Method method, Object[] args)
            throws Throwable {
        return MethodHandleUtils.invokeOn(
            MethodHandleUtils.defaultMethodHandle(method), proxy, args);
    }

    private static Object handleObjectMethod(
        Object proxy,
        Method method,
        Object[] args,
        String topicMapping,
        Class<?> api
    ) {
        return switch (method.getName()) {
            case "toString" -> api.getSimpleName()
                + "$CallProxy{topic='" + topicMapping + ".*'}";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> args != null && args.length > 0 && proxy == args[0];
            default -> throw new IllegalStateException(
                "Unsupported Object method: " + method.getName());
        };
    }
}
