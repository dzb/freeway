package com.jujin.freeway.commons.bean;

import java.lang.reflect.Constructor;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class BeanIntrospector {
    private static final ClassValue<BeanPlan> PLANS = new ClassValue<>() {
        @Override
        protected BeanPlan computeValue(Class<?> type) {
            return BeanPlan.of(type);
        }
    };
    // 注：若应用动态生成大量 class（如代理类），此处可能成为内存泄漏源。
    // 届时可替换为弱键缓存（如 WeakHashMap），但需权衡并发性能。
    private static final ConcurrentMap<Constructor<?>, BeanConstructor> CONSTRUCTORS = new ConcurrentHashMap<>();

    private BeanIntrospector() {
    }

    public static BeanPlan plan(Class<?> type) {
        return PLANS.get(Objects.requireNonNull(type, "type"));
    }

    public static BeanConstructor constructor(Constructor<?> constructor) {
        return CONSTRUCTORS.computeIfAbsent(Objects.requireNonNull(constructor, "constructor"), BeanConstructor::of);
    }
}
