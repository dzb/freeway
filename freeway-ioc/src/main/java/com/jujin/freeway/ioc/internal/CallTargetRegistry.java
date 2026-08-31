package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.commons.bean.MethodHandleUtils;
import com.jujin.freeway.ioc.DeadCallException;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
        for (Method method : target.getClass().getMethods()) {
            int mods = method.getModifiers();
            if (method.getDeclaringClass() == Object.class
                    || Modifier.isStatic(mods)
                    || method.isSynthetic()) {
                continue;
            }
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
        for (Method method : target.getClass().getMethods()) {
            int mods = method.getModifiers();
            if (method.getDeclaringClass() == Object.class
                    || Modifier.isStatic(mods)
                    || method.isSynthetic()) {
                continue;
            }
            targets.remove(
                methodTopic(topicMapping, method.getName()),
                new MethodTarget(target, method, MethodHandleUtils.methodHandle(method)));
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

    private static String methodTopic(String topicMapping, String methodName) {
        return topicMapping + "." + methodName;
    }

    private record MethodTarget(Object target, Method method, MethodHandle handle) {}
}
