# 远程 CallBus 桥接设计（Remote RPC over CloudHttpClient）

> 状态：v1/v2 **已实现**（2026-08-27 A–D 阶段 + 2026-09-12 申报式导出）；
> **v3（CallBus 自然延伸）已定稿，实施状态见文末状态注**。
> 范围：把 `freeway-ioc` 的 `CallBus` 请求-应答通道接到
> `freeway-cloud` 的 `CloudHttpClient` 传输面，实现"同一份接口代码，
> 本地走内存槽位、远端走带韧性的 HTTP"。v3 进一步把"本地还是远端"从
> 应用代码里拿掉：装载 cloud 之后，`CallBus` 自己就是跨进程的。
> 前置阅读：`EventBus`/`CallBus` javadoc（消息域三通道）、
> `freeway-cloud-unified-design.md`（§5.2 RPC、§10 明确不做）。

## 0. 设计立场

CallBus 保持**零网络感知**：它不知道 HTTP、序列化或服务发现。
远端化是"本进程服务不了的调用交给谁"这一跳——v1/v2 把这一跳交给应用
（`RemoteProxyFactory` 组装），v3 把它收进框架：CallBus 开**一个与传输无关的缝**
（`CallBridge`，§5），cloud 装载时把自己的 HTTP 桥接进去（§3.3）。

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
4. **缝开在解析上，不开在环绕上**（v3 的关键判断）：`CallBus` 的解析顺序是
   "本地 handler 槽位 → 桥 → `DeadCallException`"，桥只在本地无人应答时被问到。
   本地有 handler 的调用永远不出进程，不存在"悄悄改走网络"的翻转；
   而跨进程必然失效的两条本地语义（事务内联、Throwable 原样传播）因此只在
   **真的跨了进程**时才不成立，且由 §3.3 的边界写明。

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
             │   CallBus.call      (question, 方法对)   │──本地槽位→ handler 方法句柄派发
            │   EventBus.stream   (Flow.Publisher 视图)│
            └──────┬──────────────────────────────────┘
                   │ 出栈(两者用不同传输,各自独立)
        fact 走 MQ │            question 走 HTTP
        EventSink  │     CallBridge(cloud)→CloudHttpClient
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
  类型（ sealed interface / result record ）而不是依赖跨进程异常
  透传——这与本地用法同构，也是文档要强调的使用纪律。

### 2.4 响应 — 传输失败

不加信封，直接沿用 CloudHttpClient 的既有行为——含 1.6 起的幂等门
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
        throws CloudException;
}
```

- 内部构造请求：**元素级编码**——每个参数经 `JsonCodec` 序列化成恰好
  一个 JSON 值后以逗号拼接进 `[...]`（`null` 参数编码为 `null`），
  body 恒为 JSON 数组；再手工 `new CloudRequest("POST",
  RpcPaths.endpoint(mapping, method), Map.of("Content-Type",
  "application/json", RemoteCaller.VERSION_HEADER, RemoteCaller.VERSION),
  bytes)`——版本头经显式 header 携带（`CloudRequest.post` 便捷工厂
  不带版本头，无法用于本协议）；请求随后 `.idempotentWith(idempotent)`
  携带消费方的重放安全裁定（§3.3 幂等标记）。
- 传入的每调用超时经 `callAsync` + `orTimeout` 收敛为端到端预算
  （重试含内），到期映射为 retryable `CloudException.timeout`（§3.3）。
- 服务发现的 serviceId 来自消费方的绑定 id 约定（见 §4）。

### 3.2 server 侧：`RpcExport` 申报 + `RpcEndpoint`（cloud 新增）

```java
/** 申报"这个 mapping 可被远程调用"；handler 由容器解析。 */
binder.bind(UserHandlers.class);
binder.contribute(RpcExport.class)
    .add(RpcExport.of("user", UserHandlers.class));                    // message 不跨边界
binder.contribute(RpcExport.class)
    .add(RpcExport.of("user", UserHandlers.class).propagateMessages()); // 回传异常消息
```

框架侧：组合期贡献一条通配路由 `/rpc/{mapping}/{method}`，并在 `.before(HTTP_SERVER)` 的装配
hook 里解析导出（重复 mapping / 类型未绑定 → 启动失败）、把 handler 注册到**容器总线**。
应用因此不持有总线、编解码器或路由；`RpcEndpoint.route(export, bus, codec)` 保留给独立组装
（ext 引擎的 `RouteIndex`、自定义挂载）。

- `RpcExport.of(mapping, type)` 申报导出；框架解析 handler、注册到容器总线并服务
  `Route.post("/rpc/<mapping>/{method}", ...)`——mapping 以**路径字面量**
  参与路由，每次导出各占一个节点，因此同进程可并存多个 mapping（早先共用
  `{mapping}` 模式变量时，第二个导出会在启动期撞 `Duplicate route`）。
  导出即校验 mapping 名（`[A-Za-z0-9_.]`）；请求侧反查 CallBus
  （`handles(topic)` 门禁）→ `call` → JSON 回写 / 2.3 错误映射。
- 只发布**显式列出**的 mapping：不提供"导出全部槽位"的开关（防误暴露，
  呼应 §10 "无 CloudExporter" 的保守立场）。
- 安全归属传输层已有的 mTLS 配置（`freeway.cloud.rpc.tls.*`）；本文档
  不引入新的鉴权机制，注明部署面应将 `/rpc/*` 视为内部端点。

**v3 追加：导出即寻址（mapping 名就是被调地址）**。
`RpcExport.of("user", UserHandlers.class)` 同时表达两件事：这个 mapping
在本进程被服务（server 面），以及**本实例可以被名字 `user` 找到**（地址面）。
做法是让它成为一条注册身份：`ServiceDeclaration` 从"返回一个实例"改为
"返回本实例可被寻址的**全部身份**"（`List<ServiceInstance>`；接口 javadoc
原本就写的是 "endpoint(s)"），HTTP 面给出服务名，RPC 面给出它导出的每个
mapping。`RegistryLifecycleHook` 仍旧是唯一的注册/续租/注销方——它按身份
列表注册、对每个身份续租、停止时全部摘除，不需要第二套心跳。

- 身份推导仍只有一处：`HttpServiceDeclaration.of(container)` 给出
  endpoint/host/port/metadata，mapping 身份只换 `serviceId`（`instanceId`
  取 `<mapping>@<host>:<port>`，同一套派生规则）。
- 没有 discovery 模块也能导出（只是没人找得到它）——身份声明是 discovery
  的事，导出是 RPC 的事，两者不互相要求。
- **mapping 名与 service id 同属一个命名空间**：全局唯一。同一应用的多个
  副本导出同一个 mapping → 同一逻辑服务的多个实例，consumer 侧负载均衡
  （§3.3）。不同应用抢同一个 mapping 名与今天抢同一个 app name 是同一类
  建模错误。
- 要以"服务名 + mapping"寻址（而不是 mapping 名）时，用显式原语
  `RemoteCaller.invoke(serviceId, mapping, method, ...)`——身份注册没有取消
  服务名这条地址，只是多了一种更贴近调用点的叫法。

### 3.3 consumer 侧：`CallBus` 的自然延伸（`CallBridge`）

远程不是第二种调用方式，而是 `CallBus` 在"本进程没有 handler"时的下一跳。
cloud 装载后贡献一个**调用桥**，此后用户侧只有一种写法：

```java
// 单体：同进程 handler 命中，零序列化
// 拆分：同进程没有 handler，按 mapping 名跨进程——调用代码一字不改
@Inject CallBus bus;
UserApi api = bus.consumer("user", UserApi.class);

// 需要注入而不是就地取值时，一行绑定（CallBus javadoc 既有写法）
binder.bind(UserApi.class).to(c -> c.get(CallBus.class).consumer("user", UserApi.class));
```

**用户侧对照（v2 → v3）**：

| 用户动作 | v2 | v3 |
|---|---|---|
| 拿到远程能力 | `@Inject RemoteCaller` + `@Inject CallBus` | 不需要（装载 cloud 即生效） |
| 建代理 | `RemoteProxyFactory.of(bus, caller).serviceId(..).mapping(..).localFirst().build(Api.class)` | `bus.consumer(mapping, Api.class)` |
| 注入代理 | `binder.bind(Api.class).to(c -> factory.build(Api.class))` | `binder.bind(Api.class).to(c -> c.get(CallBus.class).consumer(mapping, Api.class))` |
| 指定对端 | `.serviceId("user")` | 无需指定：mapping 名即地址（导出即寻址，§3.2）；要以服务名寻址用 `RemoteCaller` |
| 强制远端 | `.remoteOnly()` | `RemoteCaller.invoke(...)`（显式原语，见下） |
| 重放裁定 | `@Idempotent` | `@Idempotent`（不变，由桥转给传输层） |

**解析顺序（唯一一条规则）**：本地 handler 槽位 → 桥 → `DeadCallException`。
没有模式开关：`localFirst` 不再是需要显式选择的模式，而是这条规则的结果；
`remoteOnly` 消失——要强制远端就直接说"调哪个服务"（`RemoteCaller`）。

**寻址（这个 mapping 由谁提供）**：不需要任何声明——**mapping 名就是地址**。
server 面导出 mapping 时，框架把本实例也注册成这个名字（§3.2 的"导出即寻址"），
consumer 面本地没有 handler 时按 mapping 名查同一套 discovery + 负载均衡。
于是单体与拆分只差"有没有实例注册了这个名字"，调用代码与配置都不变：

```java
@Inject CallBus bus;
UserApi api = bus.consumer("user", UserApi.class);
// 单进程：本地槽位命中
// 拆出去以后：名字 user 在 mesh 里有实例 → 同一行代码跨进程
```

**本地未命中就尝试上网——这条要说清**（桥存在时的解析顺序，唯一一条规则）：

| 情形 | 结果 | 说明 |
|---|---|---|
| 本地有 handler | 进程内派发 | 永不出进程；mesh 里有同名实例也不影响（先本地） |
| 本地没有，mesh 里有实例 | HTTP（§2 协议） | 与普通 cloud RPC 完全同路：每次尝试重新选实例（LB），重试/熔断/限流/tracing/传播全部生效 |
| 本地没有，mesh 里也没有，**方法有 default 实现** | `DeadCallException` → 代理跑 default | 你写了 default，就是声明"这个能力可以缺席"——降级语义与单进程时一致 |
| 本地没有，mesh 里也没有，**方法无 default** | `CloudException.noInstance(mapping 名)` | 响亮失败；绝不静默返回 null 或假结果 |

所以"未声明的调用会尝试上网"的准确含义是：**凡本地无人应答的调用都会先去
mesh 里按名字找一次**，找到就跨进程、找不到才回到"没人能服务"（有 default 就
降级、没有就报错）。代价是一个 discovery 查询（进程内注册表是 map 查找；
远端注册中心通常在适配器内有本地缓存），收益是调用点不需要知道进程边界。

**"缺席"与"宕机"在消费侧不可区分**（discovery 只返回存活实例）：判据放在
调用方——接口方法写了 default 视为可选能力（缺席即降级），没写 default 视为
必需依赖（找不到实例就是故障）。这条不需要新配置，声明就写在方法签名上。

**负载均衡**：桥不是新的调用通道，它就是 `CloudHttpClient` 的一次普通调用——
`discovery.getInstances(mapping)` → `loadBalancer.choose(...)`（默认轮询；
weighted/zone/canary 由应用或适配器 `.primary()` 替换），**每个传输尝试重新
选实例**，所以重试不会反复砸同一个死端点；熔断/限流按 mapping 名分组，
`@Idempotent` 决定模糊结果是否重放（§6），`InvocationContext` 照常注入出站头。

**桥拿得到什么**：`CallRequest(topic, args, method)`（§5）。带 `method` 是为了让
`@Idempotent` 继续生效——重放安全是**消费方**的裁定（§6 幂等门），只有消费方
接口知道，桥必须把这个裁定转给 `RemoteCaller`。裸调用 `bus.call(topic, args)`
没有接口方法，按非幂等处理（与今天一致）。

**边界（有意不透明化的部分）**：

- 桥仍然是一次普通 cloud RPC：传输超时、mTLS、重试/熔断/限流、tracing 全部
  沿用 `freeway.cloud.rpc.*` 与韧性三件套，**不新增第二套治理旋钮**。
- `bus.call(topic, args, timeout)` 的 timeout 仍是**调用方的耐心**（本地/远端
  一视同仁，语义不变）；需要**每调用端到端预算**时用显式原语
  `RemoteCaller.invoke(..., timeout, idempotent)`，桥这一跳的预算由传输层
  `request-timeout` 决定。
- 本地有 handler 时调用永不出进程（§0 理由 4）：事务内联与 Throwable 原样传播
  在进程内完整保留，只有真的跨进程时才换成 §6 的错误映射。
- `CallBus.handles(topic)` 仍然只回答"**本地**有没有 handler"——server 面的
  门禁（§3.2）不会因为装了桥就放行一个本机不存在的 mapping。
- 桥只处理"本地没人接"的调用，因此**不做转发/中继**：一个既没导出该 mapping
  又没实现它的节点不会变成二传手。

**`RemoteCaller` 保留为显式原语**：点名服务、每调用预算、显式幂等开关、裸
`invoke` —— 需要精确控制那一次远程调用时用它，桥内部也用它。
`RemoteProxyFactory` 删除（破坏性）：它的三个能力各有归处——本地优先 = 桥本身，
`serviceId` = mapping 名即地址（§3.2），`mode`/`timeout` = 上面两条边界。

**被否掉的替代方案**：用既有的 `advise(...)` 装远端回落（ioc 零改动）。
不成立有三条：advice 的 `CallChain` 只有 topic/payload，拿不到接口方法，
`@Idempotent` 会静默失效；`dead` 计数会把桥已服务的调用记成 dead；
advice 的注册顺序决定它包住谁，"应用的 tracing advice 与桥谁在外层"没有稳定答案。
缝要开在**解析**上，不是**环绕**上。

## 4. 配置键

v1 实现**未引入**本节早期草案中的 `rpc.remote.enabled` /
`remote.path-prefix` / `remote.serialization` 键：导出面由显式的
`RpcExport.of(mapping, type)` 声明决定（比全局开关更保守，呼应
"无 CloudExporter"），路径固定 `/rpc/{mapping}/{method}`，序列化仅
JSON。沿用既有的 `rpc.connect-timeout` / `rpc.request-timeout` /
`rpc.tls.*` / 韧性三件套，**不新增超时或 TLS 键**——远程 CallBus 就是
一次普通 cloud RPC 调用，不该有自己的第二套治理旋钮。

**v3 同样不新增键**：寻址由声明（`RpcExport` → 身份注册）与约定（mapping 名即
地址）给出，不引入 `rpc.peer.*` 之类的映射表——多一个键就多一份"两个地方都要改"
的负担，而它表达的正是导出声明已经表达过的事实。


## 5. 对 freeway-ioc 的请求（v3：一个缝）

v1/v2 是"零 API 变更"：远端化全在 cloud 侧，应用自己组装 `RemoteProxyFactory`。
v3 把这一步收进框架，因此需要**一个与传输无关的缝**——它不含网络、序列化或
发现知识，只回答"本进程服务不了的调用交给谁"：

1. **`CallRequest(String topic, List<Object> args, Method method)`**（新，ioc 顶层）
   —— 在飞的调用。`method` 是 consumer 接口方法（裸调用为 null），
   `returnType()` 由它派生（裸调用 `Object.class`）；不做 `Method` 之外的第二份
   元数据副本。
2. **`CallBridge`**（新，ioc 顶层 SPI）
   ```java
   Object call(CallRequest call) throws Throwable;   // 抛 DeadCallException 表示不接
   ```
   以 `binder.contribute(CallBridge.class)` 贡献，`CallBus` **构造时消费**
   （与 `EventBus` 消费 `EventSubscriber`/`EventSink` 同款，构造发生在所有模块
   绑定之后）。**至多一个**：两个贡献启动失败并点名双方——路由歧义不静默择一。
3. **`CallBus.CallChain.method()`**（新增访问器）：advice 与桥看到同一个调用，
   于是 tracing/重试 advice 对"桥那一跳"同样生效，且不必知道桥的存在。
4. **`CallBusStats.bridged`**（新增计数）：本地 handler 命中记 `served`，
   桥服务记 `bridged`，两者都没有才是 `dead`——统计不再把已服务的调用记成
   dead（"我的调用有多少出了进程"是装载桥之后第一个该能回答的问题）。

不变：`register`/`unregister`/`handles`（server 面门禁依赖它，语义仍为"本地"）、
`consumer`、`call` 三个公开重载（裸调用不接受 `Method`，桥按非幂等处理）、
`advise`、`DeadCallException`、close 语义。

## 6. 错误映射总表（v1 实现）

| 对端情形 | consumer 抛出 | retryable |
|---|---|---|
| handler 正常返回 | 返回值 JSON 反序列化 | — |
| handler 抛业务异常 | `CloudException`(cause=`RemoteInvocationException(classFqn, message)`) | no |
| 连接/超时/5xx | `CloudException` | connect=是；timeout/中途 I/O/5xx 仅 `@Idempotent` 操作（幂等门） |
| 4xx 非 2.3 结构 | `CloudException(status)` | no |
| 回复体无法反序列化为 returnType | `CloudException(deserialization)` | no（确定性失败） |
| 未知 `X-RPC-Version` | `CloudException(rejected)` | no |
| **本地无 handler，mesh 无实例，方法有 default** | `DeadCallException` | —（代理回落 default；"可选能力缺席"的既有降级语义） |
| **本地无 handler，mesh 无实例，方法无 default** | `CloudException.noInstance(mapping 名)` | no（部署态问题，重放无益） |

`RemoteInvocationException extends RuntimeException`，字段：
`String remoteClass`（对端异常类全名，accessor `remoteClass()`），
message 为对端消息。**不伪造原类型继承链**
（还原不可能，伪造会造成 instanceof 误导）。

## 7. 明确不做（本文档范围外）

- 分布式事务 / saga 补偿：事务内联失效是不可消除的事实，框架不兜底。
  需要最终一致性的写操作走 EventBus（outbox）不走 RPC。
- 方法级注解路由（`@RemoteService` / `@CloudClient` 之类）：与 §10 反注解魔法的
  立场一致——mapping 名已在导出时声明并成为地址（§3.2），消费方按同一个名字
  调用即可，不需要往业务接口上再挂一层路由注解。
- **按 metadata 反查导出面**（"哪些实例带 mapping `user`"）：v3 用"导出即注册
  身份"实现同一效果，且不需要 `ServiceDiscovery` 增加"枚举服务/按 metadata
  查询"的能力（那会变成每个适配器都要实现的 SPI 扩张）。
- 消费方的 mapping→服务映射表（`rpc.peer.*` 之类）：导出声明已经表达了这件事，
  再加一张表就是"同一条规则的第二处定义"。
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
| C | `RpcExport` 导出申报 + `RpcEndpoint` server 面 + `RemoteProxyFactory` 双模式；契约测试（真实双容器互调） | B |
| D | ext `freeway-http-*` 合入验证 + 文档进 DEVELOPER-GUIDE | C |
| v3 | ioc 开缝（`CallRequest`/`CallBridge`/`CallChain.method()`/`bridged`）+ cloud `HttpCallBridge` + `ServiceDeclaration` 身份列表（导出即寻址）+ 删除 `RemoteProxyFactory`；双容器互调 + "本地命中不出进程" + "别名可被同类发现"契约测试 | C |

> **状态（2026-08-27）**：A–D 全部完成，另含 `callAsync` 异步传输面与
> 每调用超时的端到端接线（原 deferred 项）。CloudHttpClient 接口新增
> `callAsync` 默认方法（default 桥接同步形式，Default 覆盖为 sendAsync
> 真异步 socket 段），resilience 编排语义两种模式完全一致。
>
> **状态（2026-09-12，v2）**：导出改为**申报式**（`RpcExport` 数据贡献 +
> 一条 `/rpc/{mapping}/{method}` 通配路由 + `.before(HTTP_SERVER)` 装配 hook），
> `RemoteCaller` 由框架绑定；`RpcEndpoint.of(...)` 删除，独立组装改用
> 容器无关的 `RpcEndpoint.route(export, bus, codec)`。
>
> **状态（2026-09-12，v3）**：consumer 侧收敛为 `CallBus` 的自然延伸——
> §3.2/§3.3/§4/§5 即本次定稿内容：ioc 开一个与传输无关的缝（`CallBridge`），
> cloud 贡献一个 HTTP 桥，**导出即寻址**（mapping 名就是被调地址，靠身份注册
> 落到 discovery，不引入任何新的配置键），删除 `RemoteProxyFactory`。
> 实施状态：**待实施**（设计评审通过后落地，落地时本行改为完成日期与验证结论）。
