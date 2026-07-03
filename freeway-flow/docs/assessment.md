# 移植适配评估：solon-flow → freeway-flow

> 评估时间：2026-06-26  
> 评估对象：将 solon-flow 核心编排引擎提取并适配到 freeway 框架

## 一、源模块分析：solon-flow

### 模块架构

solon-flow 是一个基于 Solon 框架的通用图编排引擎，支持工作流、审批流、规则引擎等场景。

```
solon-flow/
├── solon-flow/              ← 核心引擎（本次移植源）
├── solon-flow-workflow/     ← 审批流层（不移植）
├── solon-flow-projects/     ← 表达式适配器（不移植）
├── solon-flow-designer/     ← 可视化设计器（不移植）
└── solon-flow-dataflow/     ← DataFlow 模块（未构建）
```

### 核心模型

| 类 | 职责 |
|---|---|
| `Graph` | 图容器：id/title/driver/metas/nodes/links/start |
| `Node` | 节点：id/title/type/when/task/nextLinks/prevLinks |
| `Link` | 有向边：prevId/nextId/title/priority/when |
| `NodeType` | 7 种类型：START(1)/END(2)/ACTIVITY(11)/EXCLUSIVE(21)/INCLUSIVE(31)/PARALLEL(32)/LOOP(33) |

**设计原则**：Spec（可变 Builder）→ 运行时对象（不可变）。GraphSpec/NodeSpec/LinkSpec 负责解析和构建，Graph/Node/Link 是不可变运行时模型。

### 执行引擎

`FlowEngineDefault.node_run()` 是核心分发器：

```
node_run() 按 NodeType 分发：
  START     → 触发生命周期 → 沿所有 nextLink 流出
  END       → 触发生命周期 → 终止
  ACTIVITY  → 执行 task → 沿所有 nextLink 流出
  EXCLUSIVE → 找第一个条件为真的 link → 只走那条分支
  INCLUSIVE → 找所有条件为真的 link → 全部走（带 fan-in 计数）
  PARALLEL  → 等待所有入边到齐 → 所有出边并发走（可选线程池）
  LOOP      → 遍历 $for/$in 指向的集合，每次迭代走一遍
```

## 二、外部依赖分析

solon-flow 核心引擎（solon-flow 模块）的外部依赖：

| 依赖 | 用途 | 是否必须 |
|---|---|---|
| `org.noear:solon` | Solon 框架（Utils, IoC, 注解） | 工具类可替换 |
| `org.noear:solon-expression` | SnelParser 表达式解析 | 需替代 |
| `org.noear:solon-aot` | AOT/native-image 支持 | 非必须（provided scope） |
| `org.yaml:snakeyaml` | YAML 图定义解析 | 可砍（JSON only） |
| `org.noear:snack4` | JSON 序列化（ONode） | 需替代 |
| `org.noear:dami2` | 事件总线（DamiBus） | 需替代 |
| `org.noear:liquor-eval` | 脚本执行（Scripts.eval） | 需替代 |
| `org.slf4j:slf4j-api` | 日志接口 | freeway 已有 |

其中**必须替换的**有 5 个（snakeyaml 可砍，solon-aot 可砍，slf4j 已有）。

## 三、目标环境分析：freeway

### 框架特点

- JDK 25+，使用 ScopedValue、Record、Virtual Thread
- **core 模块零外部依赖**（freeway-commons 仅依赖 slf4j）
- compose-first，显式 ModuleEx 注册，无 classpath 扫描

### 可用的内置能力

| 能力 | 位置 | 用途 |
|---|---|---|
| JSON 解析/序列化 | freeway-commons `JsonUtils`/`JsonObject`/`JsonArray` | 替代 snack4 |
| IoC 容器 | freeway-ioc `Container`/`Binder` | 替代 Solon IoC |
| 类型转换 | freeway-commons `Coercer` | 类型安全 |
| Bean 内省 | freeway-commons `BeanIntrospector` | 反射工具 |
| AOP | freeway-ioc `Advisor`/`MethodAdvice` | 方法级拦截（不能替代 FlowInterceptor） |
| EventBus | freeway-ioc `EventBus` | 应用级 pub/sub（对 FlowContext 过重） |
| Module 系统 | freeway-ioc `ModuleEx` | 模块注册入口 |

### 缺失的能力

| 能力 | 说明 |
|---|---|
| 表达式求值 | freeway 完全没有表达式/脚本引擎 |
| 流程级拦截器链 | freeway AOP 是方法级的，不能感知流程节点生命周期 |
| 执行级事件总线 | freeway EventBus 是应用级单例，不适合执行级作用域 |

## 四、依赖替换方案

| solon-flow 依赖 | 用途 | freeway 替代 | 策略 |
|---|---|---|---|
| `snakeyaml` | YAML 解析 | — | **砍掉**，仅支持 JSON |
| `snack4` (ONode) | JSON 序列化 | `freeway-commons` JsonObject/JsonArray/JsonUtils | **替换** |
| `dami2` (DamiBus) | 事件总线 | `FlowEventBus`（自写 ~90 行） | **自写** |
| `liquor-eval` (Scripts) | 脚本求值 | — | **砍掉**，task 仅支持 @bean/#graph/$meta |
| `solon-expression` (SnelParser) | 条件表达式 | `ExprEvaluator`（自写 ~280 行） | **自写** |
| `solon.Utils` | 字符串判空 | `str == null \|\| str.isEmpty()` | **内联** |
| `solon.Assert` | 断言 | `Objects.requireNonNull` | **替换** |
| `solon.lang.*` | 注解 | — | **移除** |
| `solon.RankEntity` | 拦截器排序 | `RankedInterceptor` record | **自写** |

## 五、可行性结论

### ✅ 完全可行

1. **核心引擎是纯图遍历算法**——FlowEngineDefault 约 400 行的 `node_run()` 及其子方法不依赖任何外部库，可直接迁移
2. **自写代码量可控**——ExprEvaluator（280行）+ FlowEventBus（90行）+ RankedInterceptor record（10行），总计约 380 行新代码
3. **JSON 适配简单**——freeway 的 JsonObject/JsonArray API 与 snack4 的 ONode 高度相似，fromDom 改写约 50 处调用点
4. **PlantUML 导出零依赖**——纯 StringBuilder 拼接 PlantUML 文本格式，完整保留

### 风险点

| 风险 | 等级 | 缓解 |
|---|---|---|
| ExprEvaluator 边界情况 | 低 | 14 个断言覆盖 + 标准递归下降模式 |
| JsonObject 构造函数 package-private | 低 | 使用 `JsonUtils.object()` 工厂方法 |
| freeway.Container vs flow.Container 命名冲突 | 低 | 全限定名或重命名 |
| PARALLEL 节点线程安全 | 低 | CountDownLatch + AtomicReference，与 solon-flow 一致 |

### 最终依赖

```xml
<dependency>com.jujin8.freeway:freeway-commons</dependency>   <!-- JSON + 工具 -->
<dependency>com.jujin8.freeway:freeway-ioc</dependency>        <!-- IoC 容器 -->
<dependency>org.slf4j:slf4j-api</dependency>                   <!-- 日志（已有） -->
```

**零新增三方依赖。**
