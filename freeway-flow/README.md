# freeway-flow

进程内图编排引擎：加载 DAG，用带条件、网关与分支隔离的语义执行。图格式为
**Freeway 原生设计**（`version=3`，显式 entry、节点/边分离、构建期全量校验）；引擎语义由
Freeway 自行定义，不追随上游行为。7 种节点、封闭的任务词汇表、boot 期报错的校验姿态，
与框架其他模块同一种品味。

> **边界**：这是编排器，不是耐用工作流引擎——不提供跨进程持久化与断点恢复，
> 执行是同步 in-JVM 的。

## 来源与许可

引擎谱系可追溯至 [opensolon/solon-flow](https://github.com/opensolon/solon-flow) 4.0.2
（[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)）。保留的是节点分类与
若干遍历不变式；图 schema、任务词汇表、执行模型（迭代前沿行走）、join/loop 语义与校验
姿态均为 Freeway 重写。零新增三方依赖：

| solon-flow 依赖 | 用途 | freeway-flow 替代 |
|---|---|---|
| `snakeyaml` | YAML 图定义 | **移除** — 仅支持 JSON |
| `snack4` (ONode) | JSON 序列化 | `freeway-commons` JsonUtils |
| `dami2` (DamiBus) | 执行级事件总线 | `FlowEventBus`（~90 行） |
| `liquor-eval` (Scripts) | 脚本/任务求值 | **移除** — task 仅 `@name` / `#graphId` / 内联组件 |
| `solon-expression` (SnelParser) | 条件表达式 | `ExprEvaluator`（独立递归下降实现） |
| `RankEntity` | 拦截器排序 | 容器扩展链（组装期定序，加载后不可变） |

## 节点语义

| 类型 | 语义 |
|---|---|
| `START` | 入口；沿匹配的边推进 |
| `END` | 标记图完成；未达 END 且非主动停止的运行会失败 |
| `ACTIVITY` | 写 `data` → 跑 task → 沿匹配边 and-fan-out |
| `EXCLUSIVE` | 择一：首个命中的 `when`，否则默认边，否则**死端报错** |
| `INCLUSIVE` | 先 join（所有前驱分支到达各计数一次）再 run 再 fan |
| `PARALLEL` | 先 join 再 fork；`join:"merge"`（默认）分支写隔离 + 冲突检测，`"shared"` 显式退回共享 |
| `LOOP` | `$for`/`$in` 迭代 body（顺序、每次迭代重置体内 join 计数）；无 `$for` 即普通网关 |

## 词汇表（构建期即校验）

- **task**：`@name`（容器按 id 解析 `TaskComponent`/`ConditionComponent`）、`#graphId`（子图，
  未达 END 即在调用点报错）、内联组件（编程式）。v1/v2 的 `$meta`、`!marker` 已删除：
  静态值改用节点 `data` 字段，标记匹配用带 id 的 contribute + `@name`——旧写法在
  **构建期**报错并指路。
- **when（条件）**：`ExprEvaluator` 表达式（`> < >= <= == != && || !`、括号、`a.b.c` 路径、
  列表下标），或 `@name` 组件引用。所有表达式在 `create()` 编译，非法表达式启动即失败。
- **join**：仅 PARALLEL 节点的保留 meta 键，`merge`（缺省）或 `shared`。

## 快速开始（v3）

```java
String json = """
{
  "version": 3,
  "id": "demo", "entry": "s",
  "nodes": [
    { "id": "s",    "type": "start" },
    { "id": "gw",   "type": "exclusive" },
    { "id": "high", "type": "activity", "task": "@handler" },
    { "id": "low",  "type": "activity", "data": { "verdict": "存档" }, "task": "@handler" },
    { "id": "e",    "type": "end" }
  ],
  "links": [
    { "from": "s",  "to": "gw" },
    { "from": "gw", "to": "high", "when": "score > 80" },
    { "from": "gw", "to": "low",  "when": "score <= 80" },
    { "from": "high", "to": "e" },
    { "from": "low",  "to": "e" }
  ]
}""";

// IoC 装配：任务 = 带 id 的容器绑定，拦截器 = 贡献的扩展（加载期定链，之后不可变）
App app = Freeway.create(new FlowModule(), binder -> {
    binder.contribute(TaskComponent.class).add("handler", (ctx, node) ->
        System.out.println(ctx.get("verdict")));
    binder.contribute(FlowInterceptor.class).add("audit", new FlowInterceptor() {
        @Override public void onNodeStart(FlowContext ctx, Node node) {
            System.out.println("-> " + node.id());
        }
    });
});

Graph graph = Graph.fromText(json);      // 构建期校验：环/入口/词汇/表达式/join
FlowContext ctx = FlowContext.of();
ctx.put("score", 95);
app.get(FlowEngine.class).eval(graph, ctx);

// 独立使用（无容器）：只支持内联组件与 #子图
FlowEngine standalone = FlowEngine.create();
```

分支隔离：`join: "merge"`（默认）下每个 PARALLEL 分支写入线程本地缓冲，干净结束时合并；
两个分支改同一键的同一个基值 → 合并报冲突，而不是静默择一。

## 模块依赖

`freeway-ioc` + `freeway-commons` + slf4j-api（框架内模块，零新增三方）。

```
原始代码版权 (c) 2017-2025 noear.org and authors，
以 Apache License 2.0 授权；Freeway 重写保留原始许可条款。
```
