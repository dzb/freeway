# 任务解析（Task Resolution）

freeway-flow 的任务解析通过四种策略匹配图中 `task` 字段到实际执行器。

## 当前策略

| 优先级 | 策略 | 语法 | 说明 |
|--------|------|------|------|
| 1 | 内联组件 | `task.getComponent()` | 代码中直接 set 的 `TaskComponent` |
| 2 | Marker 匹配 | `!marker` | `FlowMarkerIndex` 交集匹配，最具体者胜出 |
| 3 | 子图调用 | `#graph` | 调用命名子图 |
| 4 | Bean 引用 | `@bean` | 容器 `get(TaskComponent.class, id)` |
| 5 | 元数据 | `$meta` | 从 graph meta 取值 |

## @FlowMarker

`@FlowMarker` 是 repeatable 注解，标记 `TaskComponent` 实现类：

```java
@FlowMarker("channel:email")
@FlowMarker("priority:high")
public class EmailSender implements TaskComponent {
    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        // 发送邮件
    }
}
```

图定义中通过 `!markerName` 引用：

```json
{ "task": "!channel:email !priority:high" }
```

`FlowMarkerIndex.resolve()` 按 `containsAll` 语义匹配——handler 的 marker 集必须包含节点声明的所有 marker。多个匹配时，marker 数量最多的 handler 胜出。

## 历史

> 早期版本支持"类型化任务"——按全限定类名直接引用 `TaskComponent` 实现类（如 `"com.example.StockCheckHandler"`）。该机制通过 `FlowEngine.register(Class, TaskComponent)` 和 `typedTasks()` 实现，已在 v1.3.0 移除，由 `@FlowMarker` + `!markerName` 替代。
