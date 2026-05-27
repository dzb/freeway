package com.jujin.freeway.boot.internal;

/**
 * 在 SLF4J 初始化之前，探测 classpath 并决定是否启用 JUL adapter。
 * 仅在没有任何外部 Logger 实现时激活，否则让路给外部实现。
 *
 * <p>必须通过 {@link #autoConfigure()} 在首次调用
 * {@link org.slf4j.LoggerFactory#getLogger(Class)} 之前执行。
 *
 * @since 1.0.0
 */
public final class FreewayLogging {

    private static final String JUL_PROVIDER =
            "com.jujin.freeway.commons.logging.JULLoggerServiceProvider";

    private FreewayLogging() {
    }

    /**
     * 探测并配置日志。检测规则：
     * <ol>
     *   <li>如果已设置 {@code slf4j.provider} 系统属性，尊重用户显式指定，不做任何操作</li>
     *   <li>探测 classpath 上是否存在外部 SLF4J Logger 实现（Logback、Log4j2 等）</li>
     *   <li>有外部实现 → 让路，不设置任何属性；无外部实现 → 设置 {@code slf4j.provider}
     *       指向 {@link com.jujin.freeway.commons.logging.JULLoggerServiceProvider}</li>
     * </ol>
     *
     * @return {@code true} 表示已配置 JUL provider；{@code false} 表示让路给外部实现
     */
    public static boolean autoConfigure() {
        // 1. 用户已通过 -Dslf4j.provider=xxx 显式指定，尊重用户选择
        if (System.getProperty("slf4j.provider") != null) {
            return false;
        }

        // 2. 探测 classpath 上是否存在外部 SLF4J Logger 实现
        if (hasExternalLogger()) {
            return false; // 让路给外部实现
        }

        // 3. 无外部 Logger 实现，激活 Freeway 自带的 JUL adapter
        System.setProperty("slf4j.provider", JUL_PROVIDER);
        return true;
    }

    /**
     * 通过 Class.forName 探测常见外部 Logger 实现（不触发 SLF4J 初始化）。
     * 按优先级从高到低检测。
     */
    private static boolean hasExternalLogger() {
        // Logback-classic 1.3.x+（支持 SLF4J 2.x 的 SPI 机制）
        if (classExists("ch.qos.logback.classic.spi.LogbackServiceProvider")) {
            return true;
        }
        // Log4j 2.x SLF4J binding
        if (classExists("org.apache.logging.slf4j.Log4jLoggerFactory")) {
            return true;
        }
        // reload4j / Log4j 1.x 桥接
        if (classExists("org.slf4j.reload4j.Reload4jLoggerFactory")) {
            return true;
        }
        // slf4j-simple
        if (classExists("org.slf4j.simple.SimpleServiceProvider")) {
            return true;
        }
        return false;
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className, false, FreewayLogging.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
