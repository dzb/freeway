# 类型化任务（Typed Task）

类型化任务是 freeway-flow 中通过**类名直接引用**的任务组件注册方式。
它基于 IoC 的 `Extension<TaskComponent>` 贡献机制，将"流程任务"从通用 bean 中显式分离出来。

## 概念

原有 task 解析链路：

```
@beanName  → 从 FlowContainer 查
#graphId   → 从 engine 查子图
$metaKey   → 从 context 取元数据
```

类型化任务在这条链中新增按类名匹配的策略：

```
"com.example.MyHandler" 或 "MyHandler"
  → FlowEngine.typedTasks (ConcurrentHashMap<Class<?>, TaskComponent>)
  → 匹配成功则执行
```

## 核心类

| 类 | 角色 |
|---|---|
| `TaskComponent` | 契约 — `@FunctionalInterface`，`void run(FlowContext, Node)` |
| `FlowEngineImpl` | 存储 — `ConcurrentHashMap<Class<?>, TaskComponent> typedTasks` |
| `FlowDriverDefault` | 解析 — `tryAsTypedTask()` 按全限定名或简名匹配 |
| `FlowModule` | 装配 — 从 `container.extension(TaskComponent.class)` 收集并注册 |

调用链：

```
FlowModule.registerTypedTasks()
  → engine.register(handler.getClass(), handler)

FlowDriverDefault.handleTaskDo()
  → tryAsTypedTask()
    → exchanger.engine().typedTasks()
    → entry.getValue().run(context, node)
```

## 示例：订单提交流程

```
start → 校验库存 → 计算总价 → end
          ↑
     类型化任务：按类名引用
```

### 第 1 步：实现 TaskComponent

```java
// AuditLog.java — 普通服务，会被注入到任务中
public record AuditLog(String tenant) {}

// StockCheckHandler.java — 类型化任务
public class StockCheckHandler implements TaskComponent {

    @Inject
    private AuditLog auditLog;

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        String sku = context.getAs("sku");
        int qty = Integer.parseInt(context.getAs("qty"));

        if (qty > 100) {
            throw new FlowException("库存不足：" + sku);
        }

        context.put("stockOk", true);
        System.out.println(auditLog.tenant() + " 库存校验通过: " + sku);
    }
}
```

### 第 2 步：通过 IoC 贡献注册

```java
var container = Freeway.create(binder -> {
    // 绑定依赖服务
    binder.bind(AuditLog.class).to(new AuditLog("tenant-123"));

    // 通过贡献机制注册类型化任务
    binder.contribute(TaskComponent.class).add(StockCheckHandler.class);

    // 安装 flow 引擎模块
    binder.install(new FlowModule());
});

var engine = container.get(FlowEngine.class);
```

`FlowModule` 在启动时自动扫描所有 `Extension<TaskComponent>` 贡献，
调用 `engine.register(StockCheckHandler.class, handler)`。

### 第 3 步：在图定义中用类名引用

编程式：

```java
engine.load(Graph.create("order-submit", spec -> {
    spec.addStart("start")
        .linkAdd("check-stock");

    spec.addActivity("check-stock")
        .task(StockCheckHandler.class.getName())   // 全限定类名
        .linkAdd("calc-total");

    spec.addActivity("calc-total")
        .task("@priceCalculator")                  // 传统 @bean 方式
        .linkAdd("end");

    spec.addEnd("end");
}));

var ctx = FlowContext.of();
ctx.put("sku", "SKU-001");
ctx.put("qty", "5");
engine.eval("order-submit", ctx);
```

JSON 定义：

```json
{
  "id": "order-submit",
  "nodes": [
    { "id": "start",    "type": "START",    "next": ["check-stock"] },
    { "id": "check-stock", "type": "ACTIVITY",
      "task": "com.example.StockCheckHandler",
      "next": ["calc-total"] },
    { "id": "calc-total", "type": "ACTIVITY",
      "task": "@priceCalculator",
      "next": ["end"] },
    { "id": "end",      "type": "END" }
  ]
}
```

### 解析优先级

`FlowDriverDefault.handleTaskDo()` 中的匹配顺序：

| 优先级 | 匹配方式 | task 写法 | 适用场景 |
|---|---|---|---|
| 1 | 内联组件 | `task.getComponent()` | 代码直接 set |
| 2 | **类型化任务** | `"com.example.StockCheckHandler"` | 按类型注册的任务 |
| 3 | 子图调用 | `"#subGraphId"` | 流程嵌套 |
| 4 | Bean 引用 | `"@beanName"` | IoC 容器中的 bean |
| 5 | 元数据 | `"$metaKey"` | 上下文动态取值 |

## 与 @bean 的对比

| | `@beanName` | 类型化任务 |
|---|---|---|
| 注册方式 | `binder.bind(...)` | `binder.contribute(TaskComponent.class).add(...)` |
| 引用方式 | `"@someBean"` | `"com.example.StockCheckHandler"` |
| 语义 | 通用 bean，不区分用途 | 明确标注"这是一个流程任务" |
| 发现性 | 混在所有绑定中 | 通过 `Extension<TaskComponent>` 统一收集 |
| 依赖注入 | 容器自动处理 | `FlowModule` 优先从容器 `get()` 获取（触发 `@Inject`） |
