# freeway-flow v3 图 schema（现行规格）

> 本文描述 **当前唯一** 的图格式：`version=3`。构建只认这一种形状——
> `version` 为 3、`nodes`/`links` 齐备，其余一律 `IllegalArgumentException` 并说明缺什么。
> v1（`layout` 双写法）在 1.5.2 删除；v2 在 1.5.3 的引擎再设计中升级为 v3
> （词汇表收敛 + `data` 字段 + `join` 声明 + 表达式构建期编译），不做别名兼容。
> 历史设计过程见 `graph-v2.md`（提案稿，含未采纳项）与 `migration-notes.md`。

## 1. 原则

1. **定义、编译、执行分离**：定义只表达业务图；`create()` 即编译（校验 + 只读化）；
   执行只走迭代前沿，不回头补偿结构。
2. **一切可静态知道的，都在 boot 知道**：环、入口、边引用、任务词汇、条件表达式、
   `join` 声明与取值、`data` 键——构建期失败，运行期不再遇见。
3. **词汇表封闭**：task 与 when 的合法形式是枚举而非启发式；不认识的写法报错并指路，
   绝不"猜个最像的"。
4. **运行态不回写模型**：context、停止信号、join 计数、死端标记都属于单次求值，不进图定义。
5. **显式隔离**：并行分支的写隔离与合并策略是图上的声明（`join`），不是 javadoc 里的道歉。

## 2. 形状

```json
{
  "id": "order-flow",
  "title": "Order Flow",
  "driver": "default",
  "version": 3,
  "entry": "start",
  "meta": { "biz": "order" },
  "nodes": [
    { "id": "start",    "type": "START" },
    { "id": "validate", "type": "ACTIVITY", "task": "@validateOrder" },
    { "id": "gw",       "type": "EXCLUSIVE" },
    { "id": "fork",     "type": "PARALLEL", "meta": { "join": "merge" } },
    { "id": "notify",   "type": "ACTIVITY", "task": "#notification" },
    { "id": "archive",  "type": "ACTIVITY", "data": { "verdict": "archived" } },
    { "id": "end",      "type": "END" }
  ],
  "links": [
    { "from": "start", "to": "validate" },
    { "from": "validate", "to": "gw", "when": "order.status == \"paid\"" },
    { "from": "gw", "to": "fork" },
    { "from": "fork", "to": "notify" },
    { "from": "fork", "to": "archive" },
    { "from": "notify", "to": "end" },
    { "from": "archive", "to": "end" }
  ]
}
```

### 2.1 图字段

| 字段 | 说明 |
|---|---|
| `id` | 图 id（必填、非空） |
| `title` | 展示名，可选 |
| `driver` | 驱动器 id；缺省/空白/`"default"` 走默认驱动 |
| `version` | 固定为 `3`——门禁字段 |
| `entry` | 显式入口节点 id；缺省时要求恰有一个 START |
| `meta` | 图级元数据 |
| `nodes` / `links` | 见下 |

### 2.2 节点字段

| 字段 | 说明 |
|---|---|
| `id` | 图内唯一；同时用作 PlantUML 标识符（限 `[A-Za-z_][A-Za-z0-9_]*`） |
| `type` | 必填：`START` / `END` / `ACTIVITY` / `EXCLUSIVE` / `INCLUSIVE` / `PARALLEL` / `LOOP`（大小写不敏感，其余报错并列出合法值） |
| `title` | 展示名 |
| `task` | 词汇表：`@name` / `#graphId`；缺省 = 无任务（空活动） |
| `data` | 静态值对象；节点执行时（`when` 命中后、task 之前）逐键写入 context——v2 `$meta` 任务的替代 |
| `when` | 节点级条件（表达式或 `@name`）；不命中则跳过任务但仍评估出边 |
| `meta` | 节点元数据；保留键：`join`（仅 PARALLEL，取值 `merge`\|`shared`）、`$for`/`$in`（仅 LOOP） |

### 2.3 边字段

| 字段 | 说明 |
|---|---|
| `from` / `to` | 必须引用已声明节点 |
| `when` | 边条件（表达式或 `@name`），空 = 无条件 |
| `priority` | EXCLUSIVE 的分支序（数值大者优先）与一般出边排序 |
| `title` / `meta` | 展示用 |

### 2.4 词汇表

**task**：
- `@name` → `container.get(TaskComponent.class, name)`（条件位为 `ConditionComponent`）；
  未绑定时报错并给出 bind/contribute 两种写法。
- `#graphId` → 子图调用：共享本次求值的 join 计数与死端报告；子图未达 END 在调用点抛错。
- 内联组件（编程式）直接执行。
- 其它一律构建期报错（`$x` → 指向 `data`；`!x` → 指向带 id 的贡献）。

**when（边或节点）**：
- `@name` → 条件组件引用（运行期解析）；
- 否则按 `ExprEvaluator` 文法**在构建期编译**：比较、`&& || ! and or`、括号、算术
  `+ - * / %`、字面量、`a.b.c` 路径、列表下标；JSON 来源的数值字符串按数值比较。

## 3. LOOP

`meta.$for` = 绑定变量名，`meta.$in` = 列表 / 上下文键 / `"start...end"` / `"start:end:step"`。
body 顺序迭代（无回边；图必须是 DAG），每轮重置体内 fork-join 计数；迭代数上限
100,000（配置错误要响，不能空转）。多个分支可汇聚同一 LOOP：恰好一个分支认领
（检查+取迭代器原子），其余整节点跳过。

## 4. 执行模型（v3）

- **迭代前沿**：无路径长度栈上限（20k 节点链与 10 节点同深度）；仅子图嵌套/嵌套
  并行受 authoring 深度约束。
- **停止**：`ctx.stop()` = 主动早完成，合法，不触发死端报错。拦截器不 `proceed()` =
  否决整次求值，同样合法。
- **死端响亮**：EXCLUSIVE 无命中无默认、join 未集齐 → 求值以 `FlowException` 结束并点名节点与图。
- **配对不变式**：每个 `onNodeStart` 恰有一个 `onNodeEnd`（含失败路径）。
- **分支隔离**：`merge` 分支写缓冲 + 干净合并 + 冲突即错；`shared` 为显式 opt-out。
- **失败分类**：配置错误（`IllegalArgumentException`/`IllegalStateException`）原样上抛；
  执行失败包装为 `FlowException`（`TASK_FAILED` 前缀）。

## 5. 兼容边界

不再引入历史别名。v2→v3 的破坏点（`$meta`、`!marker`、pause/resume/steps、
`FlowOptions`/`FlowInvocation`/`FlowContainer`）在 CHANGELOG 记录；旧文档/旧配置
命中这些形状时，构建期报错即迁移路径。
