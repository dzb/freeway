# 移植适配方案：solon-flow → freeway-flow

> 方案制定：2026-06-26  
> 实施完成：2026-06-26  
> 源基线：solon-flow 4.0.2

---

## 方案概述

从 solon-flow 提取核心图编排引擎（Graph → Node → Link 模型 + FlowEngine 执行引擎 + PlantUML 导出），适配为 freeway 的 `freeway-flow` 模块。

**目标：零新增三方依赖**。仅依赖 freeway-commons + freeway-ioc + slf4j（均已在 freeway 框架中）。

## 模块结构设计

```
freeway-flow/
├── pom.xml
├── README.md
├── docs/
│   ├── assessment.md      ← 移植评估
│   └── plan.md             ← 本文件
└── src/
    ├── main/java/com/jujin/freeway/flow/
    │   ├── package-info.java           ← 包级说明（源项目信息）
    │   │
    │   ├── [模型层 — 不可变运行时]
    │   │   ├── NodeType.java           枚举：7 种节点类型
    │   │   ├── Graph.java              图容器 + toPlantuml 导出
    │   │   ├── Node.java               节点
    │   │   ├── Link.java               有向边
    │   │   ├── TaskDesc.java           任务描述符
    │   │   ├── ConditionDesc.java      条件描述符
    │   │   ├── FlowTrace.java          执行痕迹
    │   │   └── NodeRecord.java         节点访问记录
    │   │
    │   ├── [模型层 — Builder/Spec]
    │   │   ├── GraphSpec.java          图定义 + JSON fromDom 解析
    │   │   ├── NodeSpec.java           节点定义
    │   │   └── LinkSpec.java           连接定义
    │   │
    │   ├── [引擎层]
    │   │   ├── FlowEngine.java          引擎接口 + eval 重载
    │   │   └── FlowEngineDefault.java      核心执行引擎（7种节点类型遍历）
    │   │
    │   ├── [运行时层]
    │   │   ├── FlowExchanger.java       执行交换器（内部状态）
    │   │   ├── FlowContext.java         上下文接口
    │   │   ├── FlowContextImpl.java     上下文实现
    │   │   ├── Temporary.java           网关临时状态（栈/计数器）
    │   │   └── FlowOptions.java         执行选项（含拦截器列表）
    │   │
    │   ├── [驱动器层]
    │   │   ├── FlowDriver.java          驱动器接口
    │   │   ├── SimpleFlowDriver.java    默认驱动器（@bean/#graph/$meta 解析）
    │   │   ├── Container.java           组件容器接口
    │   │   ├── TaskComponent.java       任务组件 @FunctionalInterface
    │   │   ├── ConditionComponent.java  条件组件 @FunctionalInterface
    │   │   └── NamedTaskComponent.java  命名任务组件
    │   │
    │   ├── [扩展层]
    │   │   ├── ExprEvaluator.java       极简条件表达式求值器（递归下降）
    │   │   ├── Stepper.java             数字范围迭代器（Loop 节点用）
    │   │   ├── FlowEventBus.java        执行级事件总线（topic pub/sub）
    │   │   ├── FlowInterceptor.java     流拦截器接口
    │   │   └── FlowInvocation.java      拦截器责任链
    │   │
    │   ├── [PlantUML 导出]
    │   │   ├── PlantumlOptions.java      输出选项
    │   │   ├── PlantumlDisplayContext.java  显示上下文
    │   │   └── PlantumlDisplayResult.java   显示结果
    │   │
    │   ├── [异常]
    │   │   └── FlowException.java        流异常
    │   │
    │   └── [Freeway 集成]
    │       └── FlowModule.java           ModuleEx 入口
    │
    └── test/java/com/jujin/freeway/flow/
        └── FlowEngineTest.java           12 个测试用例
```

**最终规模：35 个 Java 文件（含 package-info），12 个测试用例。**

## 实现步骤

### 第 1 步：创建模块骨架

- 创建 `freeway-flow/` 目录结构
- 编写 `pom.xml`（依赖 freeway-commons + freeway-ioc + slf4j + junit）
- 在父 POM 中注册模块 + dependencyManagement

### 第 2 步：迁移基础类（零内部依赖）

直接复制 solon-flow 源文件，改 package + 去注解：

| 文件 | 改动 |
|---|---|
| `NodeType.java` | `org.noear.solon.flow` → `com.jujin.freeway.flow` |
| `FlowException.java` | 同上 |
| `Container.java` | 同上 |
| `TaskComponent.java` | 去 `@Preview`/`@FunctionalInterface`（保留语义） |
| `ConditionComponent.java` | 同上 |
| `NamedTaskComponent.java` | 同上 |
| `Stepper.java` | 同上（零外部依赖） |
| `PlantumlOptions.java` | 同上 |
| `PlantumlDisplayResult.java` | 同上 |
| `PlantumlDisplayContext.java` | 同上 |
| `NodeRecord.java` | 去 `@ONodeAttr` 注解 |
| `Temporary.java` | 同上（零外部依赖，纯 JDK） |

### 第 3 步：新增自写类

| 文件 | 说明 |
|---|---|
| `ExprEvaluator.java` | ~280 行递归下降表达式解析器，支持 `>`/`<`/`==`/`!=`/`>=`/`<=`/`&&`/`\|\|`/`!`/括号/变量路径 |
| `FlowEventBus.java` | ~90 行 topic pub/sub，`ConcurrentHashMap` + `CopyOnWriteArrayList` |

### 第 4 步：迁移 Spec/模型类（需改写）

**LinkSpec.java** / **NodeSpec.java** — 纯 Java，仅需替换 `Utils.isEmpty()` → 内联判空。

**ConditionDesc.java** / **TaskDesc.java** — 同上。

**Link.java** / **Node.java** — 去掉 `transient final`（迁移到非序列化场景），`Utils.isEmpty()` → 内联。

**Graph.java** — 核心改动：
- `toJson()` / `toMap()` / `toYaml()` → 去掉 snakeyaml，`ONode.serialize` → `JsonUtils.stringify`
- `toPlantuml()` 及其辅助方法 — 完整保留，仅替换 `Utils.isEmpty()`

**GraphSpec.java** — 核心改写（~80 行）：
- `fromDom(ONode dom)` → `fromDom(JsonObject dom)`，API 映射：

| snack4 API | freeway API |
|---|---|
| `dom.get("id").getString()` | `json.getString("id")` |
| `dom.get("layout").getArray()` | `json.getArray("layout")` |
| `dom.hasKey("key")` | `json.containsKey("key")` |
| `dom.get("meta").toBean(Map.class)` | `json.getObject("meta").toMap()` |
| `linkNode.isArray()` | `linkNode instanceof JsonArray` |
| `linkNode.isObject()` | `linkNode instanceof JsonObject` |
| `linkNode.isValue()` | `linkNode instanceof String\|Number\|Boolean` |
| `new ONode(OPTIONS).asObject()` | `JsonUtils.object()` |

### 第 5 步：迁移上下文字段

**FlowTrace.java** — 纯 Java，零外部依赖，完整迁移。

**FlowContext.java / FlowContextImpl.java** — 核心改动：
- 去 `DamiBus` → `FlowEventBus`（自写）
- 去 `NonSerializable` 标记接口
- `toJson()`/`fromJson()` 中 `ONode` → `JsonObject`/`JsonUtils`
- 去弃用方法 `vars()`/`serVars()`

### 第 6 步：迁移驱动器

**FlowDriver.java** — 接口迁移，去 `@Preview`/`@NonSerializable`。

**SimpleFlowDriver.java** — 核心改动：
- 去掉 `AbstractFlowDriver` 基类（内联其逻辑）
- 去掉 `tryAsScriptTask()` 分支 — 不再支持任意脚本执行
- 条件求值交给 `ExprEvaluator`

### 第 7 步：迁移引擎核心

**FlowEngine.java** — 接口迁移，去 `ResourceUtil`（通配符加载），去 `@Preview`。

**FlowEngineDefault.java**（原 FlowEngineDefault）— 核心迁移：
- 所有内部方法从 `(exchanger, node, startNode)` 签名为 `(exchanger, options, node, startNode)` 以传递 options（包含拦截器列表）
- `eval()` 中用 `FlowInvocation` 包装
- `onNodeStart`/`onNodeEnd` 中通知所有拦截器
- 去 `RankEntity` → `FlowOptions.RankedInterceptor` record

### 第 8 步：迁移拦截器和事件总线

> 注：原方案第 5 步计划「砍掉拦截器链」，实际情况是用户在实施过程中要求保留。以下为实际执行内容。

**FlowInterceptor.java** — 接口迁移，3 个方法（interceptFlow / onNodeStart / onNodeEnd），去 `@Preview`。

**FlowInvocation.java** — 责任链迁移：
- 去 `RankEntity`，改用 `FlowOptions.RankedInterceptor` record
- `(index, target)` → `(interceptor, index)` record 实现 `Comparable`

**FlowOptions.java** — 原为拦截器列表的空壳，现实现：
- `RankedInterceptor` record（替代 solon 的 `RankEntity`）
- `interceptorAdd` / `interceptorList` 管理

### 第 9 步：Freeway 集成

**FlowModule.java**：
- 实现 `ModuleEx`，注册 `FlowEngine` 为单例
- 内置 `IocContainerAdapter`：将 freeway `Container` 适配为 flow `Container`
- 全限定名处理 `Container` 命名冲突

### 第 10 步：测试

编写 `FlowEngineTest.java`，覆盖：

| # | 测试 | 验证点 |
|---|---|---|
| 1 | testLinearFlow | START→ACTIVITY→END 线型流程 |
| 2 | testExclusiveGateway | 排他网关 + 条件分支 |
| 3 | testGraphFromJson | JSON 定义解析 + 执行 |
| 4 | testExprEvaluator | 表达式求值器 14 个断言 |
| 5 | testPlantuml | PlantUML 导出格式验证 |
| 6 | testSubGraph | #graphId 子图调用 |
| 7 | testStop | ctx.stop() 流程停止 |
| 8 | testEventBus | FlowEventBus 独立 pub/sub |
| 9 | testEventBusInFlow | EventBus 在 Flow 内部使用 |
| 10 | testInterceptorChain | 拦截器顺序 + 节点回调 |
| 11 | testInterceptorStopFlow | 拦截器阻止流程执行 |
| 12 | testMultipleInterceptors | 多拦截器嵌套顺序 |

## 与原始方案的差异

实施过程中根据用户反馈做了以下调整（均在原方案基础上**增加**，不影响兼容性）：

| 原方案 | 实际执行 | 原因 |
|---|---|---|
| 砍掉 EventBus | 保留，自写 `FlowEventBus`（~90行） | 用户要求，零依赖可行 |
| 砍掉拦截器链 | 完整保留 `FlowInterceptor` + `FlowInvocation` | 用户要求，零依赖可行 |
| 单次评估通过 | 分两轮评估：EventBus → 拦截器链 | 用户逐步确认 |
| 只做 README | 增加 `package-info.java` + `docs/` 目录 | 用户要求留存完整文档 |

## 验证结果

```
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 原始版权

```
原始代码版权 (c) 2017-2025 noear.org and authors
Licensed under the Apache License, Version 2.0

移植适配至 freeway 框架，保留原始许可证条款。
```
