# freeway-cloud 结构清晰性与代码一致性审计（2026-09-04）

**范围**：`freeway-cloud` 主代码 85 文件 / 6578 行（24 个测试类 / 141 项测试），及其依赖面
`freeway-commons` / `freeway-ioc` / `freeway-boot` / `freeway-http` 中与 cloud 交互的部分。

**目标**：结构合理清晰、代码实现简洁一致。**与 `AUDIT-CLOUD-2026-09-03.md` 不重叠** —— 那份
是功能正确性 / 安全面审计（P1 RPC 多 mapping、分片帧丢失、拒绝路径 500、secret 键脱离级联
等），本次只看结构与一致性。

**结论**

| 维度 | 评分 | 一句话 |
|---|---|---|
| 结构清晰度 | **7 / 10** | 功能域划分到位、模块边界零泄漏，但 `internal` 包承担了它名字不该承担的东西 |
| 代码一致性 | **7.5 / 10** | 日志/命名/无 TODO 等基础面相当干净，主要缺口是配置读取存在两套并存写法 |

整体判断：这是一个**已经重构过一轮**的模块（10 个功能子包 + 根包，装配分散到 10 个
`CloudXxxModule`），方向正确。剩余问题集中在**一处命名与可见性的自相矛盾**（`internal`）
和**一处抽象未被贯彻到底**（`ConfigSpec`）。两者都是机械性收口，不涉及行为变更。

---

## 一、结构清晰度

### S1 🔴 `internal` 包：26 个类型里 25 个是 `public`

包名声明"内部实现"，访问修饰符却宣称"公开可用"——这是全模块最刺眼的一处矛盾。

```
com.jujin.freeway.cloud.internal   26 类型 / 25 public
```

这 25 个 public 类型不是闭包内的辅助类，而是被 `internal` 之外的**公共包**实际引用的：

| 类型 | 被非 internal 包引用 | 类型 | 被非 internal 包引用 |
|---|---|---|---|
| `CloudHttpClientDefault` | 4 处 | `TracerDefault` | 2 处 |
| `MetricsDefault` | 2 处 | `ObjectStorageDefault` | 1 处 |
| `CircuitBreakerDefault` | 1 处 | `RateLimiterDefault` | 1 处 |
| `RetryerDefault` | 1 处 | `LoadBalancerDefault` | 1 处 |
| `SecretStoreDefault` | 1 处 | `ServiceRegistryDefault` | 1 处 |
| `ServiceDiscoveryDefault` | 1 处 | `TransportSecurityDefault` | 1 处 |

引用点几乎都在各 `CloudXxxModule.bind()` 里 —— 也就是说，这 12 个 `*Default` **就是模块的
绑定契约本身**，却住在一个叫 `internal` 的包里。

### S2 🟠 两种 `*Default` 放置惯例在项目内并存

| 模块 | `*Default` 位置 | 数量 |
|---|---|---|
| `commons` / `http` / `flow` | 与接口同包（公开包） | 6 |
| `ioc` / `db` / **`cloud`** | `internal` 包 | 17（cloud 占 12） |

CLAUDE.md 定义 `XDefault` = 「框架默认选择，**可被扩展模块通过 `.primary()` 替换**」。放在
`internal` 里，外部扩展模块（仓库外的 `freeway-ext`）无法继承或引用这个默认实现——「可替换」
在接口层面成立，在实现类层面不成立。

> 注：这不是 cloud 独自的偏差，`ioc`/`db` 同样如此。**建议先在项目层面定一个口径**，而不是
> 单独改 cloud。

### S3 🟡 包划分混用了两种维度

10 个包按**功能领域**切（`context` `discovery` `events` `health` `observe` `resilience`
`rpc` `secret` `storage` `annotation`），1 个包按**可见性**切（`internal`）。两种划分标准
并排在同一层，读代码时无法从包名推断"去哪儿找某个类的默认实现"。

### S4 🟡 `PeerConnector`（461 行）承担 5 类职责

| 职责 | 位置 |
|---|---|
| 拨号循环 + 指数退避 | `dialLoop:180` `spawnDial:209` `backoffByPeer:47` |
| 连接与握手看门狗 | `connect:222` `HANDSHAKE_TIMEOUT:39` `watchdog:55` |
| 会话/线程生命周期 | `close:237` `sessions:50` `dialers:53` |
| WebSocket 帧接收与重组 | `ClientSessionHandler:262` |
| **peer 地址解析与渲染** | `toUri:~131-179` `parsePort:166` `sameEndpoint:122` |

前四类围绕"连接"，第五类是**纯值的解析与渲染**（含 IPv6 字面量、方括号、多冒号推断），
与连接无关。抽成 `PeerAddress` record（解析 + 渲染 + `sameEndpoint`）后，这个类的职责就
收敛为"连接管理"，且地址解析这类易错逻辑能被单独单测。

### S5 🟡 `PeerHub`（426 行）承担 6 类职责

配置装配（`wire:73`）、peer 注册去重（`register:128` `duplicateToClose:154`）、拦截器
（`interceptors:37`）、服务端会话与握手校验（`ServerSessionHandler:238`）、入站帧三道门
（`receive:330`）、出站扇出。其中 `ServerSessionHandler`（92 行内部类）把**握手期的 token
校验**和**运行期的入站门禁**这两种不同性质的检查混在一个监听器里。

### S6 🟡 `CloudHttpClientDefault`：3 个 telescoping 构造器

`CloudHttpClientDefault.java:96 / 101 / 107` 三个构造器逐级叠加参数（discovery、loadBalancer、
breaker、rateLimiter、retryer…）。同时该类既做真实 HTTP 调用（`doCall:265`），又编排重试
（`attempt:252` `orchestrate:216`），还持有 per-service 的熔断器/限流器注册表
（`:79-80`，只增不驱逐——与上一轮审计的 P3-1 同源）。

> 熔断/限流/重试的**实现**已在 `resilience` 包里，这里承担的是编排。合理性中等，但把
> 「resilience 策略集」收成一个注入对象可消掉三个构造器。

### S7 🟢 模块装配粒度不均（轻微）

10 个 `CloudXxxModule` 的 `bind()` 复杂度从 3 到 13 不等
（`CloudSecretModule` 13 / `CloudDiscoveryModule` 11 / `CloudResilienceModule` 10
 vs `CloudModule` 3 / `CloudHealthModule` 3）。读过后判断是**领域本身复杂度差异**
（secret 要处理递归规避、discovery 要挂 hook），不是无谓的膨胀。仅记录，不建议拆分。

---

## 二、代码一致性

### C1 🔴 配置读取两套写法并存，且**同一方法内混用**

`ConfigSpec<T>`（位于 `commons.config`，被 `boot`/`db`/`http`/`ioc` 采用）把「键名 + 类型 +
默认值 + 解析器」声明在一起，是项目里更现代的做法。但 cloud 只贯彻了一半：

| 文件 | `resolve` 次数 | 用 `ConfigSpec` | 状态 |
|---|---|---|---|
| `resilience/CloudResilienceModule.java` | 9 | 10 | ✅ 全量采用 |
| `events/CloudEventLifecycleHook.java` | 11 | 4 | ❌ **混用** |
| `rpc/CloudRpcModule.java` | 7 | 4 | ❌ 混用 |
| `internal/HttpServiceDeclaration.java` | 7 | 2 | ❌ 混用 |
| `internal/AuthPropagator.java` | 1 | 2 | ✅ |
| `internal/ObjectStorageDefault.java` | 7 | 0 | ❌ 裸用 |
| `storage/CloudStorageModule.java` 等 4 个 | 各 1 | 0 | ❌ 裸用 |

最典型的是 `CloudEventLifecycleHook.start()`——同一个方法里两种风格并存：

```java
// 52/54/57 行：ConfigSpec 风格
if (DEDUP_ENABLED.parse(symbols.resolve(DEDUP_ENABLED.key(), null))) { ... }

// 66-71 行：裸常量风格（同一方法，相隔不到 15 行）
split(symbols.resolve(CloudConfigKeys.EVENTS_SUBSCRIPTIONS, "")),
symbols.resolve(CloudConfigKeys.EVENTS_TOKEN, "")
```

**影响**：新代码抄哪一段取决于作者先看到哪一行；键的默认值散落在 `CloudConfigKeys` 的
`*DEFAULT` 常量与调用点字面量两处（`CloudResilienceModule` 的注释明确写了"防止两层漂移"，
但这个防漂移只对用了 `ConfigSpec` 的 9 个键生效）。

**收口方式**：为剩余 8 个键声明 `ConfigSpec`，`CloudConfigKeys` 里已有的 `*DEFAULT` 常量
直接喂给 `ConfigSpec.of(...)`。纯机械替换，零行为变更。

### C2 🟠 逗号切分逻辑重复 4 次，且各有细微差异

```
internal/AuthPropagator.java:71          raw.split(",")          — 无 null/blank 守卫
internal/BaggagePropagator.java:79       raw.split(",")          — 逐对解析
secret/SecretSymbolSource.java:79        raw.split(",")          — stream + trim + 过滤空
events/CloudEventLifecycleHook.java:107  split(",")              — 有 null/blank 守卫 + trim + 过滤空
```

四处都在做同一件事（把逗号分隔配置切成 `List<String>`），但空值守卫与 trim 的有无不一致。
`commons` 中**没有**对应的共享工具。建议提一个 `ConfigLists.splitAndTrim(String)` 到
`commons`（或 cloud 内共享），四处统一。

### C3 🟠 `freeway-boot` 依赖声明在 compile 作用域，实际只被测试使用

- `freeway-cloud/pom.xml` 以**默认（compile）作用域**声明 `freeway-boot`
- `freeway-cloud/src/main/java` 对 `boot` 的引用：**0 处**
- `freeway-cloud/src/test/java` 对 `boot` 的引用：**24 处**（`BaggagePropagationTest`、
  `TracePropagationTest`、`PrincipalPropagationTest` 等用 `AppRuntime` / `FreewayApp`）

cloud 主代码用的是 `ioc` 的 `RuntimeHook`，并不需要 boot。按 Maven 语义这里应为
`<scope>test</scope>`；现状会让所有依赖 cloud 的应用**无条件传递引入 boot**。

### C4 🟡 `ConfigSpec` 的调用样板重复 11 次

`X.parse(symbols.resolve(X.key(), null))` 这个二段式在 `CloudResilienceModule` 里出现 11 次。
它是 commons javadoc 里记载的标准用法，所以**不算错误**；但 `SymbolSource`（`ioc`，同时可见
`ConfigSpec` 与自身）完全可以提供一个默认方法一步到位：

```java
default <T> T resolve(ConfigSpec<T> spec) { return spec.parse(resolve(spec.key(), null)); }
```

代价是给 ioc 的公共接口加一个方法；收益是消除全项目范围内该样板的重复。

### C5 🟢 其余：日志与命名面相当干净

- Logger：13 处全部为 `private static final Logger LOG = LoggerFactory.getLogger(...)` —— 字段名与获取方式**零偏差**
- 零 `TODO` / `FIXME` / `XXX` / `HACK`
- 零 `DefaultX` 前缀（全仓 main 代码，上一轮已清零并得到保持）
- 跨模块 `internal` 泄漏：**双向均为零** —— cloud 不引用 `commons/ioc/boot/http` 的 internal，其他模块也不引用 cloud 的 internal
- 无死依赖声明（上一轮已清除 4 个 `freeway-starter*`）

---

## 三、按收益排序的改进建议（只建议，未实施）

| 序 | 项 | 收益 | 成本 | 风险 |
|---|---|---|---|---|
| 1 | **C3** `boot` 依赖改 `test` 作用域 | 消除对下游应用的无用传递依赖 | 1 行 | 极低（需确认 cloud 运行时确无 boot 需求） |
| 2 | **C1** 剩余 8 个配置键改用 `ConfigSpec` | 消除同方法内双风格；默认值单点声明 | 机械替换 | 低（零行为变更） |
| 3 | **C2** 抽取 `splitAndTrim` 共享工具 | 消除 4 处不一致实现 | 小 | 低 |
| 4 | **S4** 抽 `PeerAddress` record | `PeerConnector` 收敛为单一职责；地址解析可单测 | 中 | 低（纯提取） |
| 5 | **S1/S2** 定 `*Default` 放置口径 | 消除包名与可见性矛盾 | **项目级决策** | 中（涉及 `ioc`/`db` 一并调整，属 API 面变动） |
| 6 | **S6** resilience 策略收为注入对象 | 消掉 3 个 telescoping 构造器 | 中 | 中（改动公共构造签名） |
| 7 | **S5** 拆分 `PeerHub` 的握手校验与入站门禁 | 两类门各自可测 | 中 | 中（`ServerSessionHandler` 重构） |
| 8 | **C4** `SymbolSource.resolve(ConfigSpec)` | 消除全项目样板 | 小 | 中（动 ioc 公共接口） |

建议的推进顺序：1–4 是低风险机械收口，可一次性做完；5 需要先在项目层面定口径；6–8 涉及
API 形状，独立排期。

---

## 四、审计方法备注

- 结构数据由脚本统计（行数、类型可见性、跨包引用计数），非人工目测。
- **工具陷阱**：本环境的 `grep` 是 ripgrep 风格的 shim，**`\|` 被当作字面量而非"或"**。
  本次审计中凡含 `\|` 的检索均已用 `-E` 重跑；早期若干结论（如"cloud 零引用 boot"）因此
  被修正——实际为"main 零引用、test 24 处"。后续在此仓库检索请统一使用 `grep -E` 或
  Grep 工具。
- 未做运行时验证（本次为静态结构审计）；基准为审计时工作树，HEAD = `44eaf032`。
