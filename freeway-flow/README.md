# freeway-flow

轻量级图编排引擎。**v1** 定义格式（`GraphSpec`）移植自 [solon-flow](https://github.com/opensolon/solon-flow)；**v2** 格式（`GraphSpec2`）为 Freeway 原生设计——显式 entry、节点/边分离、编译时校验。

## 源项目信息

| 项 | 值 |
|---|---|
| **源项目** | [opensolon/solon-flow](https://github.com/opensolon/solon-flow) |
| **原始作者** | noear (西东) |
| **移植版本** | solon-flow 4.0.2（对应 freeway-flow v1 格式） |
| **源项目许可** | Apache License 2.0 |
| **源项目活跃期** | 2025-03 ~ 至今 |

> **注意**：以下移植变更是针对 v1 (`GraphSpec`) 而言的。v2 (`GraphSpec2`) 与此无关——它是 Freeway 原生设计。

## 移植变更

solon-flow 核心引擎对外部库有较多依赖，移植过程对每一处做了裁剪/替换以实现**零新增三方依赖**：

| solon-flow 依赖 | 用途 | freeway-flow 替代 |
|---|---|---|
| `snakeyaml` | YAML 图定义解析 | **移除** — 仅支持 JSON |
| `snack4` (ONode) | JSON 序列化 | `freeway-commons` JsonObject / JsonArray / JsonUtils |
| `dami2` (DamiBus) | 执行级事件总线 | **自写** `FlowEventBus`（~90行，ConcurrentHashMap + CopyOnWriteArrayList） |
| `liquor-eval` (Scripts) | 脚本/任务求值 | **移除** — task 仅支持 @bean / #graph / $meta 三种引用 |
| `solon-expression` (SnelParser) | 条件表达式解析 | **自写** `ExprEvaluator`（~280行递归下降解析器） |
| `solon.Utils` / `solon.core.util.Assert` | 工具/断言 | JDK: `Objects.requireNonNull` / `str == null \|\| str.isEmpty()` |
| `solon.lang.*` | 注解标记 (@Preview 等) | **移除** |
| `solon.core.util.RankEntity` | 拦截器排序 | `FlowOptions.RankedInterceptor` record |

## 保留的能力（与 solon-flow 一致）

- **7 种节点类型**：START / END / ACTIVITY / EXCLUSIVE / INCLUSIVE / PARALLEL / LOOP
- **JSON 定义解析**：`Graph.fromText(json)`，格式与 solon-flow 兼容
- **条件表达式求值**：`ExprEvaluator` 支持 `>`, `<`, `>=`, `<=`, `==`, `!=`, `&&`, `||`, `!`, 括号，变量路径
- **拦截器链**：`FlowInterceptor` + `FlowInvocation`，完整责任链 + 节点生命周期回调
- **事件总线**：`FlowEventBus`，topic 主题式 pub/sub，作用域限定单次执行
- **PlantUML 导出**：`Graph.toPlantuml()`，完整保留，纯字符串拼接无外部依赖
- **执行痕迹**：`FlowTrace` + `NodeRecord`，支持暂停/恢复
- **子图调用**：`#graphId` 嵌套流程
- **编程构建**：`Graph.create(id, spec -> { ... })` Builder API

## 模块依赖

```xml
<dependency>
    <groupId>com.jujin8.freeway</groupId>
    <artifactId>freeway-commons</artifactId>  <!-- JSON + 工具 -->
</dependency>
<dependency>
    <groupId>com.jujin8.freeway</groupId>
    <artifactId>freeway-ioc</artifactId>       <!-- IoC 容器 -->
</dependency>
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>          <!-- 日志 -->
</dependency>
```

**零新增三方依赖。**

## 快速开始（v2 格式，推荐）

```java
// 1. 定义图（v2 JSON — 显式entry + 分离的nodes/links）
String json = """
{
  "id": "demo", "entry": "s",
  "nodes": [
    { "id": "s",  "type": "start" },
    { "id": "gw", "type": "exclusive" },
    { "id": "high", "type": "activity", "task": "!handler:high" },
    { "id": "low",  "type": "activity", "task": "!handler:low" },
    { "id": "e",    "type": "end" }
  ],
  "links": [
    { "from": "s",    "to": "gw" },
    { "from": "gw",   "to": "high", "when": "score > 80" },
    { "from": "gw",   "to": "low",  "when": "score <= 80" },
    { "from": "high", "to": "e" },
    { "from": "low",  "to": "e" }
  ]
}""";

// 2. 构建引擎
FlowEngine engine = FlowEngine.newInstance();
engine.register((TaskComponent) (ctx, node) -> System.out.println("高分"));
engine.register((TaskComponent) (ctx, node) -> System.out.println("低分"));

// 3. 执行
Graph graph = Graph.fromText(json);  // 自动检测 v1/v2 格式
FlowContext ctx = FlowContext.of();
ctx.put("score", 95);
engine.eval(graph, ctx);  // 输出: 高分
```

v1 (`layout`) 格式仍兼容——`Graph.fromText()` 自动检测并转换为统一运行时。

## 版权声明

```
原始代码版权 (c) 2017-2025 noear.org and authors
Licensed under the Apache License, Version 2.0

移植适配至 freeway 框架，保留原始许可证条款。
```
