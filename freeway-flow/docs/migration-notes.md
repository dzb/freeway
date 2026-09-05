# freeway-flow 迁移改动说明

> 说明对象：`freeway-flow`
>
> 背景：该模块的 **v1 定义格式 (`GraphSpec`)** 由 `solon-flow` 移植到 Freeway 架构下。本文记录移植后的实际改动和兼容策略。
>
> **v2 格式** 为 Freeway 原生设计，不来自 solon-flow——由 `GraphSpec` 以
> `version=2` 标记承载（早期类名 `GraphSpec2` 自 1.3.6 起并入 `GraphSpec`）——见 [graph-v2.md](graph-v2.md)。

## 1. 迁移目标

`freeway-flow` 的目标不是重写一套新的流程引擎，而是在 Freeway 里保留原有图编排语义，同时把依赖方式、运行边界和扩展接入方式改成 Freeway 风格。

核心原则：

1. 保留存量图定义可继续运行。
2. 运行态和模型态分离。
3. 不引入额外框架依赖。
4. 所有接入点显式化，不做 classpath 扫描。

## 2. 已完成的迁移改动

### 2.1 `FlowModule`

- 从 Solon 式的组件发现方式，改成 Freeway IoC 的显式模块绑定。
- 通过 `ModuleEx` / `Binder` 注册 `FlowEngine`。
- 通过 `IocContainerAdapter` 把 Freeway 容器映射到 flow 侧的组件解析接口。
- 保留 `TaskComponent` 的类型化注册，方便迁移后的任务处理器继续直接接入。

### 2.2 `FlowEngineDefault`

- 保留节点遍历、条件分支、子图调用和拦截器链。
- 驱动器解析改为按 graph 的 `driver` 名称显式查找。
- 默认驱动器仍作为兜底，不依赖容器扫描。
- 暂停、终止、回退等控制位保留为执行期状态，不写入图模型。

### 2.3 `FlowContextImpl`

- 上下文收敛为单次 flow 执行期对象。
- `exchanger`、`eventBus`、`trace` 只作为运行态数据存在。
- 序列化时排除 `context` 自引用。
- `trace` 兼容对象格式和旧字符串格式，便于平滑加载历史数据。

### 2.4 `FlowEventBus`

- 从全局 `DamiBus` 语义迁移为绑定在 `FlowContext` 上的本地 pub/sub。
- 订阅回调异常只隔离在当前订阅者，不影响同 topic 的其他订阅者。
- 作用域限定在单次 flow 执行内，不再承担应用级事件总线职责。

### 2.5 `GraphSpec`

- 保留 legacy 字段兼容：`layout` / `nodes`、`when` / `condition`、`link` 的多种形态。
- 序列化输出统一收敛到当前的 `layout` 结构。
- 解析时保留 start 节点推断能力，确保旧图可继续运行。
- 这是兼容策略，不是新建模默认。

### 2.6 `FlowExchanger`

- 把子图调用共享的执行态、上下文和步数计数器集中到运行期对象里。
- `reverting`、`stopped`、`interrupted` 只表示执行过程中的控制信号。
- `copy()` 只用于子图跳转场景下复用执行态。

### 2.7 `NodeType`

- 未知类型仍回退到 `ACTIVITY`。
- 这个兜底是为了兼容存量图和缺省图定义。
- 新图建议显式声明类型。

## 3. 今天补充的说明性改动

今天做的内容不是功能逻辑变更，而是把迁移声明补到代码里，避免只写抽象描述：

1. 在 `FlowContextImpl` 的类注释里补充了运行态边界、序列化兼容和自引用排除的说明。
2. 在 `FlowEventBus` 的类注释里补充了从全局总线迁移到上下文内本地事件总线的原因。
3. 在 `FlowEngineDefault` 的类注释里补充了执行态收敛、驱动器显式查找和控制位归属说明。
4. 在 `FlowModule` 的类注释里补充了 Freeway IoC 显式绑定和容器适配的原因。
5. 在 `GraphSpec` 的类注释里补充了 legacy 字段兼容和输出结构统一的原因。
6. 在 `FlowExchanger` 的类注释里补充了执行态集中管理和 `copy()` 语义。
7. 在 `NodeType` 的类注释里补充了未知类型回退策略的原因。

这些说明的目的，是让移植痕迹在代码里可见，后续看源码的人能直接理解为什么这么改，而不是只看到结果。

## 4. 兼容边界

- 兼容旧图，不代表旧写法是推荐写法。
- 运行态对象不应被当成稳定协议的一部分。
- 本地事件总线只服务单次 flow 执行，不替代应用级事件系统。
- 兜底回退策略只用于迁移期，后续新图应尽量显式声明。

## 5. 备注

如果后续再做一次结构优化，建议继续沿着这两个方向收敛：

1. 进一步压缩运行态和模型态之间的交叉引用。
2. 把旧格式兼容逻辑尽量局部化，避免兼容分支散落到多个类里。
