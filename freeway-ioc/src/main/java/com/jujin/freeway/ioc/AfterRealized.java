package com.jujin.freeway.ioc;

/**
 * 服务在容器完成 realize（实例化 &amp; 入缓存）之后触发的回调。
 *
 * <p>适用于需要在「构造函数逃离 {@code ConcurrentHashMap.computeIfAbsent} 锁域」之后
 * 再执行依赖查询的场景，从而避免 Recursive update。
 *
 * <p>注意：回调在 {@code serviceCache.putIfAbsent} 之后、
 * {@code container.get()} 返回之前执行。
 */
@FunctionalInterface
public interface AfterRealized {
    void afterRealized();
}
