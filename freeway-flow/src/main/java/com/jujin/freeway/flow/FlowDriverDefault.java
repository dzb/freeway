package com.jujin.freeway.flow;

import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 默认流驱动器
 *
 *  @author noear
 *  @since 3.0
 *
 * <p>任务描述符解析规则（与 solon-flow 一致）：
 * <ul>
 *   <li>内联 TaskComponent/ConditionComponent → 直接执行</li>
 *   <li>{@code @beanName} → 从 FlowContainer 查找组件</li>
 *   <li>{@code #graphId} → 执行子图</li>
 *   <li>{@code $metaKey} → 从 Graph meta 取值</li>
 *   <li>其他 → 使用 ExprEvaluator 求值表达式</li>
 * </ul>
 */
public class FlowDriverDefault implements FlowDriver {
    private static final FlowDriverDefault INSTANCE = new FlowDriverDefault(null, null);

    private final FlowContainer container;
    private final ExecutorService executor;

    public FlowDriverDefault(FlowContainer container, ExecutorService executor) {
        this.container = container;
        this.executor = executor;
    }

    public static FlowDriverDefault getInstance() {
        return INSTANCE;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private FlowContainer container;
        private ExecutorService executor;

        public Builder container(FlowContainer container) { this.container = container; return this; }
        public Builder executor(ExecutorService executor) { this.executor = executor; return this; }
        public FlowDriverDefault build() { return new FlowDriverDefault(container, executor); }
    }

    @Override
    public ExecutorService getExecutor() {
        return executor;
    }

    // --- condition ---

    @Override
    public boolean handleCondition(FlowExchanger exchanger, ConditionDesc condition) throws Throwable {
        return handleConditionDo(exchanger, condition, condition.getDescription());
    }

    protected boolean handleConditionDo(FlowExchanger exchanger, ConditionDesc condition, String description) throws Throwable {
        // 内联组件
        if (condition.getComponent() != null) {
            return condition.getComponent().test(exchanger.context());
        }

        // @beanName 组件引用
        if (isComponent(description)) {
            return tryAsComponentCondition(exchanger, description);
        }

        // 表达式求值
        return tryAsExprCondition(exchanger, description);
    }

    protected boolean tryAsComponentCondition(FlowExchanger exchanger, String description) throws Throwable {
        String beanName = description.substring(1);
        Object component = getContainer().getComponent(beanName);

        if (component == null) {
            throw new IllegalStateException("The condition component '" + beanName + "' not exist");
        }
        if (!(component instanceof ConditionComponent)) {
            throw new IllegalStateException("The component '" + beanName + "' is not ConditionComponent");
        }
        return ((ConditionComponent) component).test(exchanger.context());
    }

    protected boolean tryAsExprCondition(FlowExchanger exchanger, String description) {
        return ExprEvaluator.evalCondition(description, exchanger.context().data());
    }

    // --- task ---

    @Override
    public void postHandleTask(FlowExchanger exchanger, TaskDesc task) throws Throwable {
        if (task.isEmpty()) return;
        handleTaskDo(exchanger, task);
    }

    protected void handleTaskDo(FlowExchanger exchanger, TaskDesc task) throws Throwable {
        try {
            // 内联组件
            if (task.getComponent() != null) {
                task.getComponent().run(exchanger.context(), task.getNode());
                return;
            }

            // 类型化 task（从 engine 的 typedTasks 查找）
            if (tryAsTypedTask(exchanger, task)) return;

            // #graphId 子图调用
            if (isGraph(task.getDescription())) {
                tryAsGraphTask(exchanger, task, task.getDescription());
                return;
            }

            // @beanName 组件引用
            if (isComponent(task.getDescription())) {
                tryAsComponentTask(exchanger, task, task.getDescription());
                return;
            }

            // $metaKey 元数据引用
            if (task.getDescription().startsWith("$")) {
                tryAsMetaTask(exchanger, task, task.getDescription());
                return;
            }

            throw new FlowException("Unsupported task description: '" + task.getDescription()
                    + "'. Supported: @beanName, #graphId, $metaKey");
        } finally {
            exchanger.context().exchanger(exchanger);
        }
    }

    protected boolean tryAsTypedTask(FlowExchanger exchanger, TaskDesc task) throws Throwable {
        Map<Class<?>, TaskComponent> typed = exchanger.engine().typedTasks();
        if (typed.isEmpty()) return false;

        String desc = task.getDescription();
        for (var entry : typed.entrySet()) {
            if (desc.equals(entry.getKey().getName()) || desc.equals(entry.getKey().getSimpleName())) {
                entry.getValue().run(exchanger.context(), task.getNode());
                return true;
            }
        }
        return false;
    }

    protected void tryAsGraphTask(FlowExchanger exchanger, TaskDesc task, String description) throws Throwable {
        String graphId = description.substring(1);
        Graph graph = exchanger.engine().getGraphOrThrow(graphId);
        exchanger.runGraph(graph);
    }

    protected void tryAsComponentTask(FlowExchanger exchanger, TaskDesc task, String description) throws Throwable {
        String beanName = description.substring(1);
        Object component = getContainer().getComponent(beanName);

        if (component == null) {
            throw new IllegalStateException("The task component '" + beanName + "' not exist");
        }
        if (!(component instanceof TaskComponent)) {
            throw new IllegalStateException("The component '" + beanName + "' is not TaskComponent");
        }
        ((TaskComponent) component).run(exchanger.context(), task.getNode());
    }

    protected void tryAsMetaTask(FlowExchanger exchanger, TaskDesc task, String description) throws Throwable {
        String metaName = description.substring(1);
        Object val = getDepthMeta(task.getNode().getGraph().getMetas(), metaName);

        if (val instanceof String strVal && !strVal.isEmpty()) {
            // 如果是字符串，当作简单值设置到上下文
            exchanger.context().put("_meta_" + metaName, strVal);
        } else if (val != null) {
            exchanger.context().put("_meta_" + metaName, val);
        } else {
            throw new FlowException("Graph meta not found: " + metaName);
        }
    }

    @SuppressWarnings("unchecked")
    protected Object getDepthMeta(Map<String, Object> metas, String key) {
        String[] fragments = key.split("\\.");
        Object rst = null;

        for (int i = 0; i < fragments.length; i++) {
            String key1 = fragments[i];
            if (i == 0) {
                rst = metas.get(key1);
            } else if (rst instanceof Map) {
                rst = ((Map<String, Object>) rst).get(key1);
            } else {
                break;
            }

            if (rst == null) break;
        }

        return rst;
    }

    // --- helpers ---

    protected FlowContainer getContainer() {
        return container;
    }

    protected boolean isGraph(String description) {
        return description != null && description.startsWith("#");
    }

    protected boolean isComponent(String description) {
        return description != null && description.startsWith("@");
    }
}
