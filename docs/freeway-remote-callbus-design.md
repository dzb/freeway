# 远程 CallBus 桥接设计（Remote RPC over CloudHttpClient）

> 状态：**设计稿（A 阶段）** — 本文档只定契约，不含实现。
> 范围：把 `freeway-ioc` 的 `CallBus` 请求-应答通道接到
> `freeway-cloud` 的 `CloudHttpClient` 传输面，实现"同一份接口代码，
> 本地走内存槽位、远端走带韧性的 HTTP"。
> 前置阅读：`EventBus`/`CallBus` javadoc（消息域三通道）、
> `freeway-cloud-unified-design.md`（§5.2 RPC、§10 明确不做）。

## 0. 设计立场

CallBus 保持**纯本地、零网络感知**。远端化是 cloud 层对
`DeadCallException` 的消费——一个协议适配器，不是对 CallBus 的改造。

理由：

1. **分层约束**：`freeway-ioc` 是 core、零外部依赖；任何网络编解码都
   属于 cloud 层的能力面。
2. **语义不可透明化**：本地调用的事务内联（看到 mid-transaction 世界）、
   Throwable 原样传播——这两条跨进程后必然失效。框架选择把差异显式化
   （远端就是远端），而不是伪装成本地再无声翻转语义。
3. **已被预留的门**：位置参数编码当初的设计理由就是
   "wire contract independent of `-parameters`"——HTTP 编码同样受益；
   CallBus javadoc 尾句 "For remote invocation see freeway-cloud"
   承诺的正是本文档。

与 `freeway-cloud-unified-design.md` §10 的关系：§10 排除的是
*透明的*远程 bean / `@CloudClient` 注解代理 / 私有二进制协议。
本设计提供的是**显式 topic 边界的 JSON-over-HTTP**，消费者清楚自己
在调用什么：它不引入魔法注解，不造新序列化格式。

## 1. 消息域全景（本次补全最后一格）

```
            ┌─────────────────────────────────────────┐
            │              同一 JVM 内                 │
            │                                         │
            │   EventBus.publish (fact, 过去时)        │
            │   CallBus.call      (question, 方法对)   │──本地槽位→ handler 反射
            │   EventBus.stream   (Flow.Publisher 视图)│
            └──────┬──────────────────────────────────┘
                   │ 出栈(两者用不同传输,各自独立)
        fact 走 MQ │            question 走 HTTP
        EventBridge│          RemoteCaller(cloud)
        (Kafka 等) │          ┌────────────────┐
                   └─────────→│ 对端进程        │
                              │ fact→EventBus  │
                              │ question→register│
                              └────────────────┘
```

fact 与 question **必须使用不同传输**：fact 可以容忍 broker 缓冲与
at-least-once 重放，question 需要点对点即时应答且天然一次性。混走
MQ 意味着自建 correlate-id 回程路由 + 应答超时管理，等于重新发明
RPC 却没有 HTTP 的连接复用与韧性生态。

## 2. 协议定义（wire contract）

一次远程 CallBus 调用 = 一个约定形状的 HTTP POST。

### 2.1 请求

```
POST /rpc/{mapping}/{method}          ← 从 call topic "user.getUser" 直接分段
Content-Type: application/json
X-RPC-Version: 1                       ← 协议主版本,不兼容变更时递增
(Propagation headers)                  ← baggage/trace 经既有 Propagator SPI 自动进出
{ positional args as a JSON array }    ← null 元素合法(JSON null)
```

- body 始终是 JSON 数组（无参为 `[]`）——与 CallBus 的位置参数契约一致。
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
X-RPC-Exception: com.acme.InsufficientBalance   ← 异常类全名
X-RPC-Message: <URL-encoded exception message>
(body 为空)
```

- 选择 400 族而非 500：500 会被传输层韧性策略当作基础设施错误重试，
  而业务异常重放是无意义的（余额不足不会因为你再试一次就够）。
  这与 `CloudException.retryable()` 的既有分型（transport=retryable /
  client error=not retryable）严丝合缝——**业务异常天然落在 not-retryable 一侧**。
- 异常按类名+message 重建为 `RemoteInvocationException(RuntimeException)`
  在调用方抛出——**绝不尝试还原原类**（对端类可能不存在，且原本的类型
  收敛只会制造虚假的成功捕获）。需要针对特定业务异常写 catch 的场景，
  应该通过返回代数类型（ sealed interface / result record ）而不是依赖
  跨进程异常透传——这与本地用法同构，也是文档要强调的使用纪律。

### 2.4 响应 — 传输失败

不加信封，直接沿用 CloudHttpClient 的既有行为：

| 情形 | 表现 |
|---|---|
| 无实例 / 连接拒绝 | `CloudException.noInstance(...)` / connect 失败 |
| 对端 5xx 或超时 | retryable `CloudException`（可被 Retryer 重试） |
| 对端 400 族 | not-retryable `CloudException` |

三个失败源在调用方的 catch 里以类型区分：
`CloudException`(传输层) → 重试/熔断语汇；
`RemoteInvocationException`(业务层) → 具体 catch 语汇。
两层不会混淆。

### 2.5 版本与兼容

- `X-RPC-Version: 1`。服务端发现未知版本回 400 +
  `X-RPC-Message: unsupported rpc version N`。未来只在破坏性变化时
  （如改参数编码方式）递增主版本。
- 方法级的字段增删不需要版本升级：JSON 位置数组上多传或少传元素会
  在反序列化时报错并显式 4xx，属 fail-fast。
- Path 中 mapping/method 仅允许 `[A-Za-z0-9_.]`（与注册期的方法名
  约束吻合），服务端需 re-validate 防路径穿越。

## 3. 组件划分（均不触碰 freeway-ioc）

### 3.1 consumer 侧：`RemoteCaller`（cloud 新增）

```java
public final class RemoteCaller {
    // 复用 cloud 全部能力面
    public RemoteCaller(CloudHttpClient http, JsonCodec codec) { ... }

    /** CallBus.call 的远程等价物。 */
    public <T> T invoke(
        String serviceId, String mapping, String method,
        List<?> args, Class<T> returnType, Duration timeout)
        throws CloudException, RemoteInvocationException;
}
```

- 内部构造 `CloudRequest.post("/rpc/" + mapping + "/" + method, json)`。
- 超时取 min(`freeway.cloud.rpc.request-timeout`, 调用方传入值)。
- 服务发现的 serviceId 来自消费方的绑定 id 约定（见 §4）。

### 3.2 server 侧：`RpcRouteContributor`（cloud 新增）

```java
/** 把本容器注册过的 mapping 发布为 HTTP 端点。 */
binder.contribute(Route.class).add(RpcEndpoint.of("user", /* serviceId */));
```

- `RpcEndpoint.of(mapping, ...)` 生成的 route 匹配 `/rpc/{mapping}/{method}`，
  反查 CallBus 注册表（经 `handles(topic)`/新增的包私有查询面，见 §5）
  → `call` → JSON 回写 / 2.3 错误映射。
- 只发布**显式列出**的 mapping：不提供"导出全部槽位"的开关（防误暴露，
  呼应 §10 "无 CloudExporter" 的保守立场）。
- 安全归属传输层已有的 mTLS 配置（`freeway.cloud.rpc.tls.*`）；本文档
  不引入新的鉴权机制，注明部署面应将 `/rpc/*` 视为内部端点。

### 3.3 便利组合：`RemoteProxyFactory`

```java
// 用户视角——与本地 consumer() 同款手感:
UserApi api = remote.proxyFor(UserApi.class)
    .serviceId("user")            // 目标服务的 discovery id
    .mapping("user")              // call topic 前缀
    .fallbackFromDefaults()       // 可选:DeadCall(本地未命中)才出网
    .build();
```

两种模式：
1. **纯远端**：每次调用直接走 `RemoteCaller.invoke`。
2. **本地优先**：先打本地 CallBus（同进程部署的模块直连，省序列化），
   `DeadCallException` 才转远端——单体内嵌服务与拆分后形态一致的
   平滑迁移路径。

默认抛 `IllegalArgumentException` 要求显式选模式——无静默缺省。

**每调用超时**（原为 deferred 项，已实现）：`timeout(...)` 经
`CloudHttpClient.callAsync`（异步传输面，`sendAsync` socket 段）+
`orTimeout` 收窄等待；到期映射为 `CloudException.timeout`（retryable），
与传输层超时语义一致。

## 4. 配置键（CloudConfigKeys 新增）

| Key | 默认 | 说明 |
|---|---|---|
| `freeway.cloud.rpc.remote.enabled` | `false` | 总开关；不装 `RemoteRpcModule` 则一切照旧 |
| `freeway.cloud.rpc.remote.path-prefix` | `/rpc` | server 端点前缀 |
| `freeway.cloud.rpc.remote.serialization` | `json` | 预留扩展位（仅 enum 校验，v1 只有 json） |

沿用既有的 `rpc.connect-timeout` / `rpc.request-timeout` / `rpc.tls.*`
/ 韧性三件套，**不新增超时或 TLS 键**——远程 CallBus 就是一次普通
cloud RPC 调用，不该有自己的第二套治理旋钮。

## 5. 对 freeway-ioc 的最小请求

**零 API 变更**。需要确认的两点（均为现状核查而非改动）：

1. `CallBus.handles(String)` 已公开，足够做 server 端的
   "这个 mapping 是否有该方法" 判定。
2. `targets` map 的遍历面（如果 server 端想做 capability 枚举）
   目前是 private —— v1 不暴露枚举，server 端严格按用户声明的
   mapping 工作，问题闭环。（若将来需要，走 ioc 自己的演进，
   不在 cloud 里求包私有漏洞。）

## 6. 错误映射总表（v1 实现）

| 对端情形 | consumer 抛出 | retryable |
|---|---|---|
| handler 正常返回 | 返回值 JSON 反序列化 | — |
| handler 抛业务异常 | `RemoteInvocationException(classFqn, message)` | no |
| 连接/超时/5xx | `CloudException` | per 既有规则 |
| 4xx 非 2.3 结构 | `CloudException(status)` | no |
| 回复体无法反序列化为 returnType | `CloudException(deserialization)` | no（确定性失败） |
| 未知 `X-RPC-Version` | `CloudException(unsupported version)` | no |

`RemoteInvocationException extends RuntimeException`，字段：
`String exceptionClass`, `String message`, 重写 `toString` 带
"Failing class from remote handler"。**不设 getCause() 到原始类**
（还原不可能，伪造会造成 instanceof 误导）。

## 7. 明确不做（本文档范围外）

- 分布式事务 / saga 补偿：事务内联失效是不可消除的事实，框架不兜底。
  需要最终一致性的写操作走 EventBus（outbox）不走 RPC。
- 方法级注解路由（`@RemoteService` 之类）：与 §10 反注解魔法的立场
  一致，proxy 工厂的 builder 显式声明即可表达。
- 二进制/多路复用协议（gRPC 桥）：JSON over HTTP/1.1|2 已够；
  待 profile 数据说不够再做。
- 泛型返回类型的运行期重建：`Class<T>` 单层即可覆盖当前全部内部
  用例；需要复杂泛型时应用 sealed result 类型（参考 §2.3 纪律）。
- 订阅侧广播（facts）反向打进 CallBus：语法闸门（过去时 vs 方法对）
  已从源头隔离，桥不做二次过滤。

## 8. 实施切分（B/C/D 阶段预告）

| 阶段 | 内容 | 依赖 |
|---|---|---|
| A（本文档） | 协议与组件契约定稿 | 无 |
| B | `RemoteCaller` + `RemoteInvocationException` + `of()` 工厂；consumer 侧单测（MockWebServer 层面） | cloud 1.3.10 |
| C | `RpcEndpoint` server 面 + `RemoteProxyFactory` 双模式；契约测试（真实双容器互调） | B |
| D | ext `freeway-http-*` 合入验证 + 文档进 DEVELOPER-GUIDE | C |

> **状态（2026-08-27）**：A–D 全部完成，另含 `callAsync` 异步传输面与
> 每调用超时的端到端接线（原 deferred 项）。CloudHttpClient 接口新增
> `callAsync` 默认方法（default 桥接同步形式，Default 覆盖为 sendAsync
> 真异步 socket 段），resilience 编排语义两种模式完全一致。
