# freeway 1.5.7 收尾审计：冗余、收敛、一致性

> 在 [audit-coherence-1.5.7.md](audit-coherence-1.5.7.md)、
> [audit-simplification-1.5.7.md](audit-simplification-1.5.7.md)、
> [audit-redundancy-1.5.7.md](audit-redundancy-1.5.7.md) 以及随后两批落地之后的最后一轮。
> 前几轮清掉的是"第二套实现"和"第二个入口"；这一轮只找**新产生的不一致**，以及**必须承认不再收敛的边界**。
> 结论先行：只发现两处值得动的，且都已修——一处是上一轮自己引入的，一处是 API 变更后的文档漂移。

## 0. 方法与基线

- 量：core 7 模块 + demo；测试 `419 / 319 / 125 / 493 / 531 / 128 / 359`（commons / ioc / boot / http / db / flow / cloud）
  + demo 1；freeway-ext 8 模块 200 例（7 例 env-gated 跳过）。
- 自动检查：
  1. 四个模块的 `ConfigKeys` 全表（114 个键）与 `docs/freeway-config.md` 对照；
  2. 生产代码里的原始状态码与媒体类型字面量；
  3. 本轮删除/改名的 API 在活跃文档（README、docs、skills）中的残留引用；
  4. 逐文件读 diff（生产代码、测试、文档），核对形状/竞态/命名/注释。
- 基线：`mvn -Pdemos test` 与 freeway-ext `clean test` 均为绿；本轮修改后重跑。

## 1. 结论

本轮只剩两类问题：

| # | 问题 | 状态 |
|---|---|---|
| F1 | `PoolConfig` 的 wither 只剩一半字段——上一轮 R10 删掉 6 个"零调用" wither 造成的不对称 | **已修**：恢复 6 个，并加 `everyFieldHasAWither` 钉住 12 个字段 |
| F2 | 本批 API 变更后的文档漂移：README/skills 仍写 `AppStartedEvent`/`AppStoppingEvent`，`freeway-module.md` 仍用 `run(Class…)`、`Freeway.create` 混用实例与类，DEVELOPER-GUIDE 说 `run` 直接收 args | **已修**：改成 `AppLifecycleEvent` + `AppState`、`create(Class…).start()`、`run(Launch, …)` |

其余候选都属"收敛已完成，剩下的差异是设计选择"，见 §4。一个健康的信号是：这次没有再找到"同一件事两套实现"。

## 2. 逐模块

### commons
`Defer`/`ScopedCache` 两个原语 + `Scopes` 组合入口；`ScopesTest` 钉住"deferred action 仍能看到缓存值"。
仍有两个入口 + `Scoping`（ioc）共三处，但各自的职责（动作延迟、资源释放、容器作用域）不同，
`Scopes` 只负责把**顺序**这一条规则从文档变成代码；不再进一步合并，见 §4 F3。

### ioc
绑定/扩展/事件三条面子清晰；`Ordering` 是 `Contribution` 的句柄，`@Primary`/`.primary()` 是同一
marker 的两种拼写，均有 javadoc 说明。`Scoping` 是容器作用域的第三个入口（见 F3）。

### boot
`FreewayApp` 的入口阶梯现在收成两条：`run(modules)` 与 `run(Launch, modules)`；按类声明走
`create(Class…).start()`，`run` 的 javadoc 明确指向这条路径。`AppLifecycleEvent` 用一个类型 +
`AppState` 取代两个同形记录；`Launch` 是值对象，不持有第二套默认值。`ConfigCascade.bootstrapLayers`
把启动期类路径解析缓存在弱键 Map 里，日志侧不再与主级联各解析一遍。

### http
状态码与媒体类型各只有一个来源：生产响应路径全部用 `HttpStatus`（cloud 也改用），
`MediaTypes.JSON` 收编 cloud 的 4 处硬编码；`Compression.gzipStream` 让流式 gzip 也走同一入口。
`HttpStatus` 里仍有未被引用的常量（公开词表），不删——删了会让调用方重新写数字。

### db
`PoolConfig` 恢复为"每个字段一个 wither"，`PoolConfigTest.everyFieldHasAWither` 逐字段钉住；
`Sql` 的 upsert 已收敛为语义化 API；`Digests` 是哈希的唯一入口。

### flow
`TaskDesc`/`ConditionDesc` 共用包内 `Ref`（公开访问器不变）；`Node` 的 4 个重复访问器、
`FlowEvaluation.runTask`、`Graph.nodeOrThrow` 已删除；`PlantUmlSubject` 是 node/link 的唯一显示模型。

### cloud
密钥比较只有 `ConstantTime.isEqual`；SHA-256 只有 `Digests.sha256Hex`；三个出站 `HttpClient`
共用 `CloudHttpClients` 的 connectTimeout/TLS 基础；half-open 探测的 interval 与 timeout 分开
（`max(3 × interval, 1 s)`）。`CloudEvents` 的 `datacontenttype` 仍是字面量——它是 CloudEvents
属性，不是 HTTP 媒体类型（见 §4 F7）。

## 3. 本轮修的两处

### F1 · `PoolConfig` 的半套 wither（上一轮的反转）

证据：上一轮以"零调用"删除了 `withUrl/withUsername/withPassword/withMaxIdleTime/withCleanInterval/
withHealthCheckTimeout`，剩下 `withMaxSize/withMinIdle/withConnectionTimeout/withMaxLifetime/
withHealthCheckQuery/withQueryTimeout`。这违反仓库自己的形状规则："一个只差一两个字段的值 =
`defaults()` + 每个字段一个 wither"；删掉一半后，调用方无法再逐字段调整这个值，而"零调用"只说明
**没有测试**，不是"没有用途"。
修复：恢复 6 个 wither；`PoolConfigTest` 新增 `everyFieldHasAWither`，12 个字段逐一断言。
这比"删干净"更符合一致性：要么全有，要么全无；现在选择全有，因为 `defaults()` 是主入口，
wither 是它的逐字段延伸。

### F2 · API 变更后的文档漂移

- README（两处）与 skills（`SKILL.zh.md`、`references/boot.md`）仍写两个生命周期事件类型；
- `docs/freeway-module.md` 顶部示例仍是 `FreewayApp.run(OrderModule.class, …)`，而 `run` 已只收
  `ModuleEx`；同页 `Freeway.create(new OrderModule(), CloudModule.class)` 混用实例与类（两个重载
  都不接受混合形式）；"run(A.class, A.class)" 也应按新入口写成 `create(A.class, A.class)`；
- `docs/DEVELOPER-GUIDE.md` 说 "`FreewayApp.run(...)` accepts command-line args"，现在参数是
  `Launch`。
修复：全部改为当前 API（`AppLifecycleEvent` + `AppState`、`create(Class…).start()`、
`run(Launch, …)`），并保留"按类声明是常规形式"的表述——它仍然成立，只是走 launcher 的 `create`。

## 4. 已接受、不再动的差异

| # | 差异 | 为什么不再收敛 |
|---|---|---|
| F3 | ~~`Defer`、`ScopedCache`、`Scopes`、`Scoping` 四个作用域入口~~ **已实施（S2）** | `Defer`/`ScopedCache` 已共用一个 `ScopeSession`（一状态、一关闭算法；`CURRENT` + `CACHE` 视图）；value scope 在 action scope 内附着，关闭顺序固定为动作→值，访问窗口只覆盖 cache 块/drain；`Scopes`/`DeferScope`/`ScopedCache.Session` 已删除，`ScopeSession` 只读，rollback 归 `Defer` |
| F4 | `TaskDesc`/`ConditionDesc` 两个公开描述类型 | 两者服务于 `FlowDriver.handleTask` / `handleCondition` 两个回调，owner 类型不同（Node/Graph）；包内 `Ref` 已消除重复逻辑，合并公开形状只会让驱动接口更抽象而不更清楚 |
| F5 | `Launch`（值）与 `FreewayApp` 链上的 `args`/`autoDiscovery` | `Launch` 是"一次性启动"的数据载体，不持有第二套默认值；链式 API 仍是需要配置容器/类加载器时的完整入口。两者在 javadoc 里各有说明 |
| F6 | 按类声明只在 `create(Class…)`，`run` 只收实例 | `run` 是"立即启动"的便捷入口，`create` 是完整链；`run` 的 javadoc 已指向 `create`，类声明的"常规形式"没有改变 |
| F7 | `CloudEventEnvelope` 的 `"application/json"` | 那是 CloudEvents 的 `datacontenttype` 属性，不是 HTTP `Content-Type`；把它改成 `MediaTypes.JSON` 会把 HTTP 词汇耦合进事件序列化 |
| F8 | `HttpStatus` 的未使用常量 | 它是公开状态词表；生产路径已统一，删除未用常量只会让调用方回到裸数字 |
| F9 | `ConfigCascade` 的静态弱键缓存 | 是启动期重复解析的优化，不是契约；`AppLogSourceTest` 已钉住行为，缓存命中与否不影响可观察结果 |
| F10 | `Contribution`/`HealthCheck` javadoc 里的 `send(200, …)` 示例 | 只是示例；前者在 ioc（不能依赖 http），后者是文档。生产代码没有裸状态码 |

## 5. 一致性清单（本轮核对结果）

- **配置键 ↔ 文档**：114 个 `ConfigKeys` 全字面量，`docs/freeway-config.md` 覆盖率 114/114；
  没有"声明了但无人读"的键（上一轮曾疑似 4 个 `*.type`，实际由 `BackendTypeGuard` 读取）。
- **HTTP 媒体类型**：生产代码中不再有 HTTP 的 `"application/json"`/`"text/plain"` 字面量；
  唯一剩余是 CloudEvents 属性（F7）。
- **状态码**：cloud 响应路径全部 `HttpStatus`；残留的裸数字只在 javadoc 示例（F10）。
- **API 残留**：README/docs/skills 中已无 `AppStartedEvent`/`AppStoppingEvent`、`run(args…)`、
  `run(Class…)`、`withUrl(…)` 等旧写法（排除历史审计报告与 CHANGELOG）。
- **跨仓**：freeway-ext 使用本批的 `FreewayApp.run(modules)` 与 `PoolConfig.defaults(...)`，
  未引用 `Launch`/被删 wither；core ABI 变更后 ext `clean test` + `spotless:check` 绿。
- **测试**：core 全量 + demo 1 绿；本轮又补了 `PoolConfigTest.everyFieldHasAWither`。

## 6. 分模块评分（1–5）

| 模块 | 定位 | 机制 | 概念 | 边界 | 规则 | 文档 |
|---|---:|---:|---:|---:|---:|---:|
| commons | 4 | 4 | 4 | 4 | 4 | 4 |
| ioc | 5 | 5 | 4 | 5 | 4 | 4 |
| boot | 5 | 5 | 4 | 5 | 4 | 4 |
| http | 4 | 4 | 4 | 4 | 4 | 4 |
| db | 5 | 4 | 4 | 5 | 4 | 4 |
| flow | 4 | 4 | 4 | 4 | 4 | 4 |
| cloud | 4 | 4 | 4 | 4 | 4 | 4 |

## 7. 收尾建议

1. 本批可以提交：没有新的行为风险，测试与 ext 都绿。
2. R1 的完整 session 原语（F3）已按 S2 实施：`Defer`/`ScopedCache` 共用一个 `ScopeSession`，
   `Scopes` 与两个旧句柄已删除；本轮没有剩余的 L 项。
3. 不建议再为"更少的概念"做纯形状 churn：本轮唯一被证明该动的是自己上一轮造成的半套 wither，
   而它恰好说明"零调用"不能单独作为删除依据。
