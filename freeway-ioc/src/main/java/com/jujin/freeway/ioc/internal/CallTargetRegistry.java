package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.commons.bean.MethodHandleUtils;
import com.jujin.freeway.ioc.DeadCallException;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Internal registry of call targets for {@link com.jujin.freeway.ioc.CallBus}.
 */
public final class CallTargetRegistry {

    private final ConcurrentHashMap<String, MethodTarget> targets = new ConcurrentHashMap<>();

    public void register(String topicMapping, Object target) {
        Map<String, Method> seen = new HashMap<>();
        for (Method method : eligibleMethods(target)) {
            String topic = methodTopic(topicMapping, method.getName());
            if (seen.put(method.getName(), method) != null) {
                throw new IllegalArgumentException(
                    "Overloaded methods are not supported on call topics: "
                        + target.getClass().getName() + "#" + method.getName());
            }
            targets.put(topic, new MethodTarget(target, method,
                MethodHandleUtils.methodHandle(method)));
        }
    }

    public void unregister(String topicMapping, Object target) {
        // Match the STORED entry by handler identity, not by a freshly-built
        // MethodTarget: Method/MethodHandle equality is identity, and new
        // reflection copies are not guaranteed to equal the ones captured at
        // register time. Comparing the handler keeps unregister reliable.
        for (Method method : eligibleMethods(target)) {
            targets.computeIfPresent(methodTopic(topicMapping, method.getName()),
                (topic, existing) -> existing.target() == target ? null : existing);
        }
    }

    public boolean handles(String topic) {
        return targets.containsKey(topic);
    }

    public Object dispatch(String topic, List<?> payload, CallStats stats) throws Throwable {
        MethodTarget target = targets.get(topic);
        if (target == null) {
            stats.dead();
            throw new DeadCallException(topic);
        }
        Object[] args = payload == null ? new Object[0] : payload.toArray();
        Object result = MethodHandleUtils.invokeOn(target.handle(), target.target(), args);
        stats.served();
        return result;
    }

    public void clear() {
        targets.clear();
    }

    private static List<Method> eligibleMethods(Object target) {
        List<Method> methods = new ArrayList<>();
        for (Method method : target.getClass().getMethods()) {
            int mods = method.getModifiers();
            if (method.getDeclaringClass() == Object.class
                    || Modifier.isStatic(mods)
                    || method.isSynthetic()) {
                continue;
            }
            methods.add(method);
        }
        return methods;
    }

    private static String methodTopic(String topicMapping, String methodName) {
        return topicMapping + "." + methodName;
    }

    private record MethodTarget(Object target, Method method, MethodHandle handle) {}
}
