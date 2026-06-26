package com.jujin.freeway.flow;

/**
 * 命名的任务组件（用于硬编码构建图）
 *
 * @author noear
 * @since 3.8.1
 */
public interface NamedTaskComponent extends TaskComponent {
    /**
     * 获取组件名
     */
    String name();

    /**
     * 获取显示标题
     */
    String title();
}
