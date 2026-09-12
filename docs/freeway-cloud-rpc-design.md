# 远程调用设计（Remote Invocation over CloudHttpClient）

> 状态：**已实现**（协议与组件自 2026-08-27 起；2026-09-12 起导出改为申报式、
> 调用总线删除，见文末修订记录）。
> 范围：`freeway-cloud` 的跨进程调用面——provider 用 `RpcExport` 声明导出，
> consumer 用 `RemoteProxyFactory` 得到类型化客户端，传输走 `CloudHttpClient`
> （发现 + 负载均衡 + 韧性 + 传播）。同进程调用不在这里：那是绑定表上的方法调用。
> 前置阅读：`freeway-cloud-unified-design.md`（§5.2 RPC、§10 明确不做）。

## 0. 设计立场

**调用（question）与广播（fact）是两件事，各有归宿：**

| 场景 | 机制 |
|---|---|
| 同进程调用 | 绑定表 + 方法调用（`@Inject Api`）——编译期检查，事务内联与异常传播就是 Java 语义 |
| 跨进程调用 | 本文档：声明导出 + 类型化客户端 + HTTP |
| 事实广播 | `EventBus`（进程内订阅 + 可选 MQ sink） |

**"本地优先、远端兜底"的透明层（`CallBus` 及其远端桥）已删除**，理由是一次
复盘而不是一次直觉：

1. **同进程能力与 IoC 注入重合**。互相依赖的两个服务容器能直接解析（惰性代理），
   所以"打破依赖环"不再是它的理由；"可选能力缺席即降级"在 IoC 里就是绑一个
   默认实现，比 default 方法更显式。
2. **运行期热替换与框架立场冲突**。模块组合是数据、绑定在任何解析之前完成；
   框架其他部分都不支持运行期换 provider，调用通道不该是唯一例外。
3. **它独有的只有"结构化接口"**（消费方声明一份形状副本，零编译期边）。
   同样效果在组合根放一个适配器即可，而且换回编译期检查。
4. **代价却在增长**。core 里 718 行主体 + 1076 行测试、5 个公开类型、第二套
   advice 机制、第二条服务解析路径；为了让"本地调用悄悄变远端"成立，还得再开
   一条桥接缝、加调用请求类型与统计维度、改发现侧的身份注册——每一条都在把
   "部署拓扑"这个事实藏起来（该设计曾定稿为 v3，未实施即撤回）。

于是跨进程调用保持显式：**位置由组合决定**（绑本地实现还是绑远端客户端）、
**导出由声明决定**（`RpcExport`）、**地址由服务发现给出**（`serviceId`）。
调用点始终是 `@Inject Api`——单体与拆分之间的差异只出现在组合根。

## 1. 通道全景

```
            ┌──────────────────────────────────────────┐
            │               同一 JVM 内                  │
            │   EventBus.publish  (fact, 过去时)         │──订阅→ 消费者
            │   @Inject Api       (question, 方法调用)   │──绑定表→ 实现
            │   EventBus.stream   (Flow.Publisher 视图)  │
            └──────┬───────────────────────────────────┘
                   │ 出栈（fact 与 question 用不同传输，各自独立）
        fact 走 MQ │            question 走 HTTP
        EventSink  │   RemoteProxyFactory → RemoteCaller
        (Kafka 等) │          ┌──────────────────────────┐
                   └─────────→│ 对端进程                  │
                              │ fact→EventBus             │
                              │ question→RpcExport 的 handler │
                              └──────────────────────────┘
```

fact 与 question **必须使用不同传输**：fact 可以容忍 broker 缓冲与
at-least-once 重放，question 需要点对点即时应答且天然一次性。混走
MQ 意味着自建 correlate-id 回程路由 + 应答超时管理，等于重新发明
RPC 却没有 HTTP 的连接复用与韧性生态。

## 2. 协议定义（wire contract）

一次远程调用 = 一个约定形状的 HTTP POST。

### 2.1 请求

```
POST /rpc/{mapping}/{method}          ← 由 "mapping + 方法名" 直接分段
Content-Type: application/json
X-RPC-Version: 1                       ← 协议主版本,不兼容变更时递增
(Propagation headers)                  ← baggage/trace 经既有 Propagator SPI 自动进出
{ positional args as a JSON array }    ← null 元素合法(JSON null)
```

- body 始终是 JSON 数组（无参为 `[]`）——位置参数契约，不依赖编译器
  `-parameters`，也不依赖两边方法签名以外的任何约定。
- 参数对象由消费方 `JsonCodec` 序列化。两边共享 record/bean 形状时零配置；
  形状漂移在对端反序列化时失败，表现为远端 4xx。
- `GET` 不用于 RPC（所有调用统一 POST，杜绝方法动词语义分歧）。

### 2.2 响应 — 成功

```
200 OK
Content-Type: application/json
{ 应答值的 JSON }
```

- 返回值直接编码（不再包一层信封）。`void` 方法回空体，消费方映射 `null`。
- 状态码一律 200 表示"handler 正常完成并返回"；业务失败走 2.3。

### 2.3 响应 — 业务异常

```
400 Bad Request        ← handler 抛出的任何业务异常(不含 500)
X-RPC-Exception: com.acme.InsufficientBalance   ← 异常类全名(URL-encoded)
X-RPC-Message: <URL-encoded exception message>  ← 仅 propagateMessage=true
(body 为 {"error":"<异常类名>"})
```

- 选择 400 族而非 500：500 会被传输层韧性策略当作基础设施错误重试，
  而业务异常重放是无意义的（余额不足不会因为你再试一次就够）。
  这与 `CloudException.retryable()` 的既有分型（transport=retryable /
  client error=not retryable）严丝合缝——**业务异常天然落在 not-retryable 一侧**。
- 异常类名总是跨边界（调用方派发契约的一部分）；**自由文本 message
  默认不跨边界**（服务端回 `"remote handler failed"` 占位，原文只留在
  服务端日志），`RpcExport.of(...).propagateMessages()` 显式开启
  才回传——消息常携带 SQL、主机名等内部细节。
- 调用方侧重建为 `RemoteInvocationException(RuntimeException)`，作为
  非 retryable `CloudException` 的 **cause** 携带（见 §2.4/§6）——**绝不
  尝试还原原类**（对端类可能不存在，且原本的类型收敛只会制造虚假的
  成功捕获）。需要针对特定业务异常写 catch 的场景，应该通过返回代数
  类型（sealed interface / result record）而不是依赖跨进程异常
  透传——这与本地用法同构，也是文档要强调的使用纪律。

### 2.4 响应 — 传输失败

不加信封，直接沿用 CloudHttpClient 的既有行为——含幂等门
（timeout/中途 I/O/5xx 属"结果未知"，仅幂等操作重放）：

| 情形 | 表现 |
|---|---|
| 无实例 / 连接拒绝 | `CloudException.noInstance(...)` / connect 失败（请求未出本进程，任何操作都可重试） |
| 对端 5xx 或超时 | retryable `CloudException`，`outcomeUnknown()==true` —— **仅 `@Idempotent` 操作重放**（线上动词是 POST，默认不重放） |
| 对端 400 族 | not-retryable `CloudException` |

三个失败源在调用方的 catch 里以同一顶层类型区分：
`CloudException` 一律是顶层异常——传输失败（连接/超时/5xx）retryable、
无 RIE cause；业务失败 retryable=false 且 **cause 为
`RemoteInvocationException`**（`remoteClass()` 携带对端异常类名）。
两层不会混淆。connect 类失败（连接拒绝/连接超时）请求未送达，重放对
任何操作都安全；其余模糊结局的重放安全性与 `@Idempotent` 标记
（consumer 接口方法或整个接口）一致——未标记的操作在首个模糊结局
即失败，绝不重放。

### 2.5 版本与兼容

- `X-RPC-Version: 1`。服务端发现未知版本回 400 +
  `X-RPC-Message: unsupported rpc version N`。未来只在破坏性变化时
  （如改参数编码方式）递增主版本。
- 方法级的字段增删不需要版本升级：JSON 位置数组上多传或少传元素会
  在反序列化时报错并显式 4xx，属 fail-fast。
- Path 中 mapping/method 仅允许 `[A-Za-z0-9_.]`（与声明期的校验吻合），
  服务端 re-validate 防路径穿越。

## 3. 组件划分

### 3.1 consumer 侧：`RemoteCaller` + `RemoteProxyFactory`

`RemoteCaller`（cloud）是传输原语：把 `serviceId + mapping + method + 位置参数`
变成一次 `CloudHttpClient` 调用，并做 §6 的错误映射。它不持有任何本地注册表，
也不做"本地优先"判断——那是被删除的 `CallBus` 时代的事。

`RemoteProxyFactory` 在其上生成类型化客户端：

```java
// 组合根：把接口绑到远端客户端（或绑到本地实现——两者调用点相同）
binder.bind(UserApi.class).to(container -> RemoteProxyFactory
    .of(container.get(RemoteCaller.class))
    .serviceId("user-service")     // 目标服务的 discovery id
    .mapping("user")               // provider 导出的 call topic 前缀
    .timeout(Duration.ofSeconds(5)) // 可选：端到端预算（重试含内）
    .build(UserApi.class));
```

- **调用点不变**：业务代码始终 `@Inject UserApi`。单体里绑本地实现，拆出去
  以后绑远端客户端——"这个服务在哪"是组合（部署形态）的事实，不是业务代码
  的事实。这也是接口可以共享而模块之间不需要互相依赖的原因。
- 参数位置编码、`void` 映射 `null`、Object 方法（`toString`/`hashCode`/`equals`）
  本地应答，其余全部上线。
- **每调用超时**：`timeout(...)` 经 `CloudHttpClient.callAsync` + `orTimeout`
  收窄等待；到期映射为 retryable `CloudException.timeout`，与传输层超时一致。
- **幂等标记**：每次派发反射读取接口方法/接口级 `@Idempotent`，转发给
  `RemoteCaller.invoke(..., idempotent)`，落到 `CloudRequest.idempotentWith(...)`
  ——决定传输层幂等门是否放行 timeout/中途 I/O/5xx 的重放。它不是路由魔法，
  只是消费方对重放安全的声明（不改变派发，只收紧传输层一个默认不安全的行为）。

直接调用（不建代理）用 `RemoteCaller.invoke(serviceId, mapping, method, args, type)`
或其带预算/幂等开关的重载。

### 3.2 server 侧：`RpcExport` 申报 + `RpcEndpoint`

provider 只声明"这个 mapping 可被远程调用、由谁服务"：

```java
binder.bind(UserHandlers.class);
binder.contribute(RpcExport.class)
    .add(RpcExport.of("user", UserHandlers.class));                    // message 不跨边界
binder.contribute(RpcExport.class)
    .add(RpcExport.of("user", UserHandlers.class).propagateMessages()); // 回传异常消息
```

框架侧：

- 组合期贡献**一条** `/rpc/{mapping}/{method}` 通配路由（绑定期写入，先于任何
  hook，因此不受 hook 顺序影响）；
- 一个 `.before(HTTP_SERVER)` 的装配 hook 解析导出：从容器取 handler（注入、
  单例、生命周期归容器）、建方法派发表 `RpcTarget`；
- 请求到达时按 mapping 查表（未导出 → 404），按方法名取方法句柄（未知方法 →
  404），解码位置参数，**直接调用 handler**——没有中间注册表，也就不存在
  "端点查一条、客户端查另一条"的可能。

启动期能查的全查，全部**启动失败并点名**：重复 mapping、类型未绑定（消息给出
要补的 `binder.bind(...)`）、方法重载（位置参数无法区分重载，静默选一个就是
掷硬币）。导出即校验 mapping 名（`[A-Za-z0-9_.]`）。

`RpcEndpoint.route(export, handler, codec)` 保留为**容器无关的独立组装**入口
（ext 引擎的 `RouteIndex`、自定义挂载）：它接收已经拿到的 handler 实例。

- 只发布**显式列出**的 mapping：不提供"导出全部方法"的开关（防误暴露，
  呼应 §10 "无 CloudExporter" 的保守立场）。
- 安全归属传输层已有的 mTLS 配置（`freeway.cloud.rpc.tls.*`）；本文档
  不引入新的鉴权机制，注明部署面应将 `/rpc/*` 视为内部端点。

### 3.3 缺席与故障

- **能力可缺席**：consumer 侧绑一个默认实现（或换绑本地实现）即可，
  IoC 的绑定就是那句声明；不需要在接口上写 default 方法等运行期兜底。
- **实例不在**：`CloudException.noInstance(serviceId)`——部署态问题，重放无益。
  没有"悄悄降级成默认值"的路径：调用失败必须到达调用方。

## 4. 配置键（无新增）

本设计**未引入**早期草案中的 `rpc.remote.enabled` / `remote.path-prefix` /
`remote.serialization` 键：导出面由显式的 `RpcExport.of(mapping, type)` 声明
决定（比全局开关更保守），路径固定 `/rpc/{mapping}/{method}`，序列化仅 JSON。
沿用既有的 `rpc.connect-timeout` / `rpc.request-timeout` / `rpc.tls.*` /
韧性三件套，**不新增超时或 TLS 键**——远程调用就是一次普通 cloud RPC，
不该有自己的第二套治理旋钮。

## 5. 对 freeway-ioc 的请求

**零**。调用通道不再需要 core 提供任何东西：

- 同进程调用走绑定表（既有）；
- 跨进程调用的两端都在 cloud（`RpcExport`/`RpcEndpoint`/`RemoteCaller`/
  `RemoteProxyFactory`），handler 由容器解析、用方法句柄直接调用；
- 事实通道是 `EventBus`（既有）；`freeway-ioc` 里没有调用总线，
  也没有为它准备的桥接缝。

## 6. 错误映射总表

`CloudException.kind()` 是这张表的结构化形态（一个值对应一种调用方动作），下面每一行都点名它：

| 对端情形 | consumer 抛出（`kind()`） | retryable |
|---|---|---|
| handler 正常返回 | 返回值 JSON 反序列化 | — |
| handler 抛业务异常 | `CloudException` `BUSINESS`(cause=`RemoteInvocationException(classFqn, message)`) | no |
| 连接/超时/5xx | `CloudException` `CONNECT`/`TIMEOUT`/`TRANSPORT`/`HTTP` | connect=是；timeout/中途 I/O/5xx 仅 `@Idempotent` 操作（幂等门） |
| 4xx 非 2.3 结构 | `CloudException` `REJECTED`(status) | no |
| 回复体无法反序列化为 returnType | `CloudException` `REPLY_UNREADABLE` | no（确定性失败） |
| 未知 `X-RPC-Version` | `CloudException` `REJECTED` | no |
| 无存活实例 | `CloudException` `NO_INSTANCE` | no（部署态问题，重放无益） |
| 未导出的 mapping / 未知方法 | `CloudException` `REJECTED`(404) | no（声明即边界） |

`RemoteInvocationException extends RuntimeException`，字段：
`String remoteClass`（对端异常类全名，accessor `remoteClass()`），
message 为对端消息。**不伪造原类型继承链**
（还原不可能，伪造会造成 instanceof 误导）。

## 7. 明确不做（本文档范围外）

- **透明的本地优先调用**（"本地没人接就悄悄上网"）：已随 `CallBus` 一起删除，
  理由见 §0。调用是本地还是远端写在组合里（绑定），不藏在运行期。
- 分布式事务 / saga 补偿：事务内联失效是不可消除的事实，框架不兜底。
  需要最终一致性的写操作走 EventBus（outbox）不走 RPC。
- 注解路由 / `@CloudClient` 透明 bean：与 `freeway-cloud-unified-design.md`
  §10 的立场一致——导出与调用都是显式声明，不往业务接口上挂路由注解。
- 按 capability 发现（"谁导出了 mapping X"）：地址是 `serviceId`，由消费方在
  组合里点名；扩展发现面需要每个适配器实现新的查询契约，收益不抵成本。
- 二进制/多路复用协议（gRPC 桥）：JSON over HTTP/1.1|2 已够；
  待 profile 数据说不够再做。
- 泛型返回类型的运行期重建：`Class<T>` 单层即可覆盖当前全部内部
  用例；需要复杂泛型时应用 sealed result 类型（参考 §2.3 纪律）。
- 事实反向打进调用通道：fact 与 question 的语法闸门已从源头隔离。

## 8. 修订记录

| 时间 | 变更 |
|---|---|
| 2026-08-27 | v1 实现：`RemoteCaller` + `RemoteInvocationException` + `RemoteProxyFactory`（本地优先/纯远端双模式）+ `RpcEndpoint.of(...)`；`callAsync` 异步传输面与每调用超时端到端接线 |
| 2026-09-12 | v2：导出改为**申报式**——`RpcExport` 数据贡献 + 一条 `/rpc/{mapping}/{method}` 通配路由 + `.before(HTTP_SERVER)` 装配 hook；`RemoteCaller` 由框架绑定；`RpcEndpoint.of(...)` 删除，独立组装改用 `RpcEndpoint.route(...)` |
| 2026-09-12 | v3 定稿未实施即撤回：曾设计 `CallBridge` 让 `CallBus` 本地未命中时自动跨进程 |
| 2026-09-12 | v4：**删除 `CallBus`** 及其卫星类型（见 §0）；`RpcEndpoint` 直接调用容器解析出的 handler（方法派发表 `RpcTarget`，重载在启动期失败）；`RemoteProxyFactory` 收敛为纯远端（`of(RemoteCaller)`，无 mode/localFirst）；独立组装签名改为 `RpcEndpoint.route(export, handler, codec)`；文档由 `freeway-remote-callbus-design.md` 更名为本文档 |
