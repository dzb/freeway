# freeway-cloud 设计（定稿）

## 1. 文档说明

> **状态：设计基线（2026-08-19 起，2026-09-12 合并定稿）**。本文是
> `freeway-cloud` 唯一的设计基线，合并并取代 `freeway-cloud-unified-design.md`
> （总设计）、`freeway-cloud-events-design.md`（事件网格）、
> `freeway-cloud-rpc-design.md`（跨进程调用）与
> `freeway-cloud-implementation-plan.md`（实施计划，阶段清单已全部落地，
> 仍然有效的部分并入 §8.3）。
>
> **文档分工**：仓库约定见 `AGENTS.md`；模块边界、注入注解、配置级联与
> 生命周期机制见 `docs/ARCHITECTURE.md`；配置键的语义、默认值与示例见
> `docs/freeway-config.md`；用法索引见 `docs/DEVELOPER-GUIDE.md`。本文只讲
> cloud 的设计与边界，**不重复上述内容，也不逐键罗列配置**（§6 给形态分类
> 与索引）。**源码是最终权威**：与代码不一致时以代码为准，并回改本文。

### 目录

- [2. 定位与原则](#2-定位与原则) ｜ [3. 模块与装配](#3-模块与装配) ｜
  [4. 核心对象](#4-核心对象) ｜ [6. 配置键总览](#6-配置键总览) ｜
  [7. 明确不做](#7-明确不做) ｜ [8. 路线图](#8-路线图) ｜
  [9. 修订记录](#9-修订记录)
- [5. 能力设计](#5-能力设计)：
  [5.1 注册发现](#51-注册发现-discovery) ｜ [5.2 跨进程调用](#52-跨进程调用-rpc) ｜
  [5.3 跨节点事件](#53-跨节点事件-events) ｜ [5.4 可观测性](#54-可观测性-observe) ｜
  [5.5 韧性](#55-韧性-resilience) ｜ [5.6 健康检查](#56-健康检查-health) ｜
  [5.7 密钥](#57-密钥-secret) ｜ [5.8 对象存储](#58-对象存储-storage) ｜
  [5.9 服务间安全](#59-服务间安全)

## 2. 定位与原则

`freeway-cloud` 是 **core 模块**（不是 ext 适配器集合）：它提供云原生能力
的**接缝 + 可运行的本地默认**，让应用在没有任何第三方云 SDK 的情况下
启动、调用、观测、限流、探活。真正依赖外部基础设施的实现（Nacos/Consul、
S3、Vault、OTel、gRPC）是**可选替代**，由扩展模块用 `.primary()` 覆盖默认。

原则（新增能力时逐条对照）：

1. **零第三方依赖**：编译期只依赖 `commons` / `ioc` / `http` + SLF4J
   （`boot` 与 JUnit 仅测试域）。第三方 SDK 一律留在 ext 适配器里。
2. **显式声明，无魔法**：无类路径扫描、无字节码织入、无透明远程 bean。
   导出、订阅、绑定都写在组合里；出厂配置不猜。
3. **默认可运行**：每个 SPI 都有生产级默认（质量 bar 参照
   `JsonCodecDefault` / `PoolDefault`），不是占位桩；不装任何外部后端也能
   完整跑起来。
4. **装配即选择**：实现的选择发生在绑定表（`.primary()` / `@Local` 标记 /
   装哪个模块），不在运行期根据配置字符串反射找类。`freeway.cloud.*.type`
   是**声明键**而非选择键（§6）。
5. **接口在功能包，接线在 `*Module`**：`XDefault` 默认实现与其接口同包；
   `internal/` 只放非公开、无稳定性承诺的实现细节。
6. **能力可缺席**：不装某个子模块，就用不到它的键、路由与线程；缺席不
   等于降级，也不产生静默失败——调用失败必须到达调用方。

## 3. 模块与装配

```
freeway-cloud  (com.jujin.freeway.cloud)
  编译依赖：freeway-commons, freeway-ioc, freeway-http + slf4j-api
  测试依赖：freeway-boot, junit
```

`freeway-http` 只用来提供 WebServer / 路由面：cloud 在其上贡献
`/health/live|ready`、`/metrics`、`POST /rpc/{mapping}/{method}` 与
`WS /cloud/event` 端点，并以 `HttpFilter` 扩展点接入传播与追踪。依赖方向
单向（cloud → http），http 零外部依赖。

### 3.1 模块与片段

`CloudModules.standard()` 返回一个**组合片段**（`ModuleNode`）：命名节点
`freeway-cloud` 下挂 8 个叶子模块 `CloudContextModule` / `CloudSecretModule` /
`CloudDiscoveryModule` / `CloudRpcModule` / `CloudObserveModule` /
`CloudResilienceModule` / `CloudHealthModule` / `CloudStorageModule`。绑定全在
叶子模块里，片段本身不绑定任何东西，容器在 bind 之前就拿到整棵树。

- **片段是值，不是伞模块**：`ModuleEx` 不再有 `subModules()`（见
  `docs/freeway-module.md`）。应用可以把它整体放进树、嵌进自己的分组，或者
  只取其中一个模块：
  ```java
  FreewayApp.run(ModuleNode.app("order-service",
      OrderModule.class,
      CloudModules.standard()));          // 整包
  FreewayApp.run(ModuleNode.app("order-service",
      CloudRpcModule.class));             // 只要一个，按自己的方式配
  ```
  旧的 `CloudModule` 做不到这一点：它拥有自己的子模块，而同一个 class 的两个
  实例会被拒绝，所以"替换其中一个"只能整体不用伞模块。
- `CloudEventModule` 是**可选 add-on，不在片段内**：它会打开自己的监听与出站
  拨号，需要 WebSocket 事件网格时显式安装。
- **装配校验在构建树时完成**：同一实例重复到达折叠（共享片段是正常用法），
  同 class 的第二个实例直接 `IllegalStateException` 并把两条路径都点出来。

### 3.2 包结构

```
com.jujin.freeway.cloud
├── CloudConfigKeys / CloudModules / CloudHooks
├── annotation/   @Local 后端标记
├── context/      InvocationContext, TraceContext, PrincipalContext, Baggage,
│                 Propagator, CloudContextModule
├── discovery/    Endpoint, Health, ServiceInstance, ServiceDiscovery(+Default),
│                 ServiceRegistry(+Default), ServiceDeclaration,
│                 LoadBalancer(+Default), CloudDiscoveryModule
├── rpc/          CloudHttpClient(+Default), CloudRequest/CloudResponse,
│                 CloudException, TransportSecurity(+Default), RemoteCaller,
│                 RemoteProxyFactory, Idempotent, RpcExport, RpcEndpoint,
│                 CloudRpcModule
├── event/        CloudEventModule, PeerHub, PeerConnection, CloudEventEnvelope,
│                 CloudEventInterceptor（可选 add-on）
├── observe/      Tracer(+Default), MetricsDefault, MetricsSnapshot, CloudObserveModule
├── resilience/   CircuitBreaker/RateLimiter/Retryer(+Default), CloudResilienceModule
├── health/       CloudHealthContributor, HealthResult, CloudHealthModule
├── secret/       SecretStore(+Default), SecretSymbolSource, CloudSecretModule
├── storage/      ObjectStorage(+Default), ObjectMetadata/ObjectEntry/PutResult,
│                 StorageException, ObjectStoredEvent/ObjectDeletedEvent,
│                 CloudStorageModule
└── internal/     非公开实现细节：内置 Propagator、PropagationFilter/TracingFilter、
                  ReadyHandler、MetricsHandler、RegistryStore、RegistryRenewal、
                  RegistryLifecycleHook、DiscoveryConnectionHook、
                  HttpServiceDeclaration、BackendTypeGuard、ActiveBindingProbe
```

可见性是刻意的：`internal/` 里的类型可以 `public`（跨包装配需要），但不在
稳定性承诺内；反过来 `PeerConnector` / `CloudEventSink` / `RpcTarget` /
`RpcExportHook` / `RpcPaths` 是**包内实现**，`PeerHub` / `PeerConnection`
是 public 的**检视面**（运维与测试读连接状态），不是扩展点。

### 3.3 后端标记与装配协议

- 组合单元是 `ModuleNode`（ioc），云侧只提供片段工厂 `CloudModules.standard()`。
- 本地默认实现统一经 `.marker(Local.class)` 绑定，装配面可以
  `@Inject @Local ServiceDiscovery` 明确点名"内置的那个"。`@Local` 只允许
  用在**参数与字段**上——没有任何代码从类上读它，标在实现类上会编译通过
  却静默无效，因此直接把 `TYPE` 位置去掉了，误用即编译错误。
- 替代实现 = 绑定同一接口并 `.primary()`；自带后端注解的适配器自行定义
  并注册 marker（core 不预铺 `@Nacos` / `@S3` / `@OTel` 之类）。
- 装配面若引用了未注册的 marker，ioc 忽略该注解并回落到默认绑定，每个
  `(annotation, owner)` 组合告警一次。
- 后端类型键（`freeway.cloud.secret/discovery/registry/storage.type`）在
  本地实现仍然生效时，`BackendTypeGuard` 启动告警一次——**不静默丢弃**。

### 3.4 RuntimeHook 排序

hook 名集中在 `CloudHooks`，跨模块排序只引用常量：

```
start:
  freeway.cloud.discovery / rpc   before("freeway.http.server")  连接租约、解析导出、挂 /rpc 路由
  freeway.http.server                                            服务器启动，host:port 可知
  freeway.cloud.registry / event  after ("freeway.http.server")  统一注册 + 心跳；网格 origin = 实际身份
  freeway.cloud.secret / storage / resilience                     只做启动期校验，无排序约束

stop（逆序）:
  freeway.cloud.event             摘 sink、关连接（1001 going away）
  freeway.cloud.registry          先摘流量（deregister），按 shutdown-drain 留传播窗口（auto = 问注册表后端）
  RPC 传输                         等待在途调用（shutdown-grace）
  freeway.http.server             关服务器
```

- RPC/发现 hook 必须在服务器启动**之前**：调用不可能在路由未挂好时到达
  （`freeway.cloud.rpc` 排在 `HTTP_SERVER` 前是这条保证的实现）。
- 注册 hook 必须在服务器启动**之后**：地址只有启动后才知道。
- 网格端点先于 hook 存在，握手前的拨号被 `1013 not wired` 关闭并按退避
  重连——这是正常现象，不是故障。
- 注册 hook 的排序引用要求 `HTTP_SERVER` 存在：`freeway-http` 经
  `META-INF/services` SPI 默认自动发现；显式关闭 `autoDiscovery` 又不装
  `HttpModule` 时启动失败（`HookLifecycle` 严格校验），这是刻意设计。

## 4. 核心对象

| 对象 | 职责 |
|---|---|
| `serviceId`（普通字符串，无公开类型） | 是谁 |
| `ServiceInstance` | 有哪些可用实例 |
| `Endpoint` | 怎么到达 |
| `Health` | 实例当下能不能用（发现层维护，不在实例里） |
| `InvocationContext` | 跨边界传播什么 |

`ServiceId` **有意不是公开类型**（见 `docs/ARCHITECTURE.md`）：服务 id 就是
字符串，归一化（trim + 非空守卫）由 ioc 的 `ServiceIds.normalize` 在绑定 id
上隐式完成；`ServiceInstance.serviceId()`、`Container.get(type, id)`、
`CloudHttpClient.call(serviceId, …)` 共用同一个字符串，cloud 不另造包装类型
或第二套归一化。

```java
public record Endpoint(String scheme, String host, int port, String basePath) {
    public URI uri();
}

public record ServiceInstance(
    String serviceId, String instanceId, Endpoint endpoint, Map<String,String> metadata) {}

public record Health(boolean live, boolean ready, Instant lastSeen) {
    public static Health up();        // 注册即就绪
    public static Health starting();  // 已登记、尚未就绪
    public boolean isStale(Duration maxAge);
}
```

- `Endpoint` 构造期规范化 basePath（空 → `""`、补 `/`、去尾斜杠）并校验
  整个元组可渲染为合法 URI——坏定位符在装配期报错，不留到请求线程。
  IPv6 字面量按 RFC 3986 加方括号（容忍已带括号的入参）。
- `instanceId` 与位置解耦：容器重调度换 IP/端口仍是同一实例，只更新
  `endpoint`。
- `metadata` 是自由袋（zone/version/weight/canary），读取走类型化 accessor +
  默认值短路；`loadBalancer.choose(instances)` 只收到 `live && ready` 且未
  过期的实例。
- `InvocationContext` 是 `ScopedValue` 槽，只有三类子上下文：`TraceContext`
  （基础设施拥有）、`PrincipalContext`（安全拥有，不可伪造）、`Baggage`
  （应用拥有）。不承载业务数据、配置快照或对象缓存。进程内异步传播用
  `ContextExecutor`；**MDC 只作显示层**（ThreadLocal 型、不跨线程）。
- 传播统一走 `Propagator`：入站 `extract(headers)` 返回部分上下文，由
  `PropagationFilter` 按"非空胜出"合并；出站 `inject(ctx, headers)`。
  内置 `TracePropagator`（W3C `traceparent`/`tracestate`）、`AuthPropagator`
  （`x-principal` / `x-principal-roles`）、`BaggagePropagator`（W3C
  `baggage`）。新增关注点 = 贡献一个 `Propagator`，不改 core。

## 5. 能力设计

### 5.1 注册发现 discovery

| 角色 | 契约 |
|---|---|
| `ServiceRegistry` | `register(instance)` / `renew(serviceId, instanceId)` / `unregister(instance)`（生命周期） |
| `ServiceDiscovery` | `getInstances(serviceId)`；`getInstance` 是无次序的 default 便捷方法 |
| `ServiceDeclaration` | 扩展点：任何模块声明"本次启动要注册什么端点" |
| `LoadBalancer` | `choose(List<ServiceInstance>)`，只做出站前的实例选择 |

- `renew` 返回 **boolean**：`false` 表示注册表已不认识这个实例（被驱逐、
  租约过期、后端重启），调用方应重新 `register`。心跳自愈由此成立——注册
  表对不持有的实例返回 `true`，等于让调用方误以为可达。
- `ServiceDeclaration.resolve(container)` 在 HTTP 服务器启动后调用
  （host:port 已确定），内建 `HttpServiceDeclaration` 注册 `WebServer` 地址；
  其它协议/多端口由模块各自贡献。跨进程发现的后端适配器（Nacos/K8s
  endpoints 等）走 `.primary()`（**未交付**，§8.1）。
- serviceId 取 `freeway.cloud.registry.service-id` → `freeway.app.name`；
  instanceId 默认派生，可用 `registry.service-instance-id` 钉住。
- **身份的两个 auto**（值写不出来，只能推导，且启动时打一行说明选了哪个）：
  `registry.service-scheme=auto`（默认）跟随 HTTP 服务器是否启用 TLS
  （`WebServer.secure()`）——注册的 `http/https` 与网格拨号的 `ws/wss` 出自
  同一次推导，开 TLS 不会漏改而注册出 `http://`；`registry.service-host=auto`
  （默认）在服务器绑定具体地址时就用那个地址（它只在那里监听），绑定
  `0.0.0.0`/`::` 时优先 `POD_IP`、其次首个可路由本地地址，都没有才回落绑定
  地址并启动告警。多网卡主机显式点名。
- `registry.shutdown-drain=auto`（默认）由注册表后端回答
  （`ServiceRegistry.drainWindow()`）：内置进程内注册表答 `0s`（同 JVM 无传播
  延迟），注册中心适配器答自己的传播窗口（Nacos/K8s endpoints 通常几秒），
  不必每个部署各写一遍；显式时长优先，负值启动失败。
- 静态第三方服务（PostgreSQL/Redis 等）由配置给定地址，**不走 discovery**。
- `LoadBalancerDefault` 是跨虚拟线程安全的 round-robin。zone/weight/canary
  目前只是 metadata：默认策略**不读**它们（§8.2），自定义策略 bind 一个
  primary 实现即可（接口是 `@FunctionalInterface`）。

### 5.2 跨进程调用 rpc

调用模型：**同进程 = 绑定表方法调用；跨进程 = 显式申报的 RPC**。没有调用
总线，没有"本地没人接就上网"的路径——调用是本地还是远端写在组合（绑定）
里，不藏在运行期。

**导出（服务端）**

```java
public record RpcExport(String mapping, Class<?> type, boolean propagateMessage) {
    public static RpcExport of(String mapping, Class<?> type);
    public RpcExport propagateMessages();   // 允许把异常 message 带过边界
}
```

`RpcExport` 是**数据贡献**：`CloudRpcModule` 在 `HTTP_SERVER` 之前解析全部
申报，为每个 mapping 构建一张方法表（`RpcTarget`，`MethodHandle` 派发），
再挂一条通配路由 `POST /rpc/{mapping}/{method}`。导出面**只包含显式列出的
mapping**，没有"导出全部方法"的开关。mapping 重名、方法重载冲突在启动期
失败，不是运行期 404。

`RpcEndpoint.route(export, handler, codec)` 是**容器无关的独立组装**入口
（ext 引擎的 `RouteIndex`、自定义挂载）：它接收已经拿到的 handler 实例。

**消费（客户端）**

```java
binder.bind(UserApi.class).to(c -> RemoteProxyFactory
    .of(c.get(RemoteCaller.class))
    .serviceId("user-service").mapping("user").timeout(deadline)
    .build(UserApi.class));
```

`RemoteProxyFactory` 只有纯远端一种模式（`of(RemoteCaller)`）。`RemoteCaller`
是原始传输面：`invoke(serviceId, mapping, method, args, returnType
[, timeout [, idempotent]])`。`CloudHttpClient` 是更下一层的通用 HTTP 面
（`call` / `callAsync`，异步只把最后一段 socket 等待移出调用线程，编排不变）。

**线上协议 v1**

| 项 | 值 |
|---|---|
| 方法/路径 | `POST /rpc/{mapping}/{method}` |
| 版本头 | `X-RPC-Version: 1`；缺失或不匹配 → `400`（`REJECTED`） |
| 请求体 | 位置式 JSON 数组，逐元素 `JsonCodec.toJson`，`null` 显式编码 |
| 段约束 | mapping/method 限 `[A-Za-z0-9_.]`——这是线上路径段，不是自由文本 |

成功：`200` + `application/json`；handler 返回 `null` → `200` 空体，consumer
得 `null`。

业务失败：`400`，**类恒跨界**，消息只在 `.propagateMessages()` 时跨界。

| 头/体 | 内容 |
|---|---|
| `X-RPC-Exception` | 对端异常类全名（form-encoded） |
| `X-RPC-Message` | 对端消息；未申报时为 `remote handler failed` |
| body | `{"error": …}` |

拒绝（调用形状不合法）：`404` 未导出的 mapping / 未知方法；`400` 版本不匹配
/ 参数数组畸形。响应带 `X-RPC-Reject-Reason`。头值一律 form-encoded：它经常
携带控制字符，HTTP 层拒绝 CTL，否则会把一个谨慎的 4xx 变成未处理的 500。

**错误映射总表**——`CloudException.kind()` 是这张表的结构化形态，一个值
对应一种调用方动作：

| 对端情形 | consumer 抛出（`kind()`） | retryable |
|---|---|---|
| handler 正常返回 | 返回值 JSON 反序列化 | — |
| handler 抛业务异常 | `BUSINESS`（cause = `RemoteInvocationException(classFqn, message)`） | no |
| 连接失败 / 连接期超时 | `CONNECT` | 是（请求未出本进程） |
| 无回复 / 中途 I/O / 5xx | `TIMEOUT` / `TRANSPORT` / `HTTP` | 仅幂等操作（结果未知） |
| 4xx 非上表结构 | `REJECTED`(status) | no |
| 回复体无法反序列化为 returnType | `REPLY_UNREADABLE` | no（确定性失败） |
| 未知 `X-RPC-Version` / 未导出 mapping / 未知方法 | `REJECTED` | no（声明即边界） |
| 无存活实例 | `NO_INSTANCE` | no（部署态问题，重放无益） |
| 熔断打开 / 本地限流 | `CIRCUIT_OPEN` / `RATE_LIMITED` | no |
| 调用线程被中断 | `INTERRUPTED` | no |
| 出站前的本地缺陷（坏 URL/头、discovery 缺陷） | `DISPATCH` | no |
| 其它已映射失败 | `OTHER` | no |

`RemoteInvocationException extends RuntimeException`，字段 `remoteClass`
（对端异常类全名）与 message（对端消息）。**不伪造原类型继承链**：还原
不可能，伪造会造成 instanceof 误导。

**幂等与重放门**：重试必须重新选实例（换 discovery 刷新后的不同实例），
不 hammer 死实例；重放前过两道门——① 类别门（连接失败/超时 retryable）；
② 幂等门（timeout / 中途 I/O / 5xx 属"结果未知"，仅对幂等操作重放）。
`CloudRequest` 按动词派生幂等性（RFC 9110：GET/HEAD/PUT/DELETE/OPTIONS/TRACE
幂等，POST/PATCH/未知否），`idempotentWith(...)` 显式覆盖；RPC 线上恒为
POST，幂等性由 consumer 接口的 `@Idempotent`（方法级或接口级）声明，每次
调用反射读取。模糊结局的熔断计数不受幂等门影响——它仍是真实的服务失败。

**关停**：出站传输等待在途调用最多 `freeway.cloud.rpc.shutdown-grace`
（默认 5s），到点仍未结算的调用被取消而不是挂住容器关闭。

### 5.3 跨节点事件 events

立场：**本地派发不变**。`EventBus` 依旧是进程内广播 + 流的主通道，事件网格
只是它的一个 `EventSink`（出站）与一个入站漏斗（进站），不改变本地语义。
跨节点是**至多一次（at-most-once）**，并如实说明——不做 ack/重投，需要
可靠投递时用 MQ（ext 的 `freeway-mq-kafka`）。

**连接与握手**（`WS /cloud/event`，子协议 `freeway.event.v1`）：

```json
→ {"proto":1,"origin":"order-1","serviceId":"order","subscribe":["order."],"token":"…"}
← {"proto":1,"accept":true,"origin":"user-1","subscribe":["user."]}
```

- 首个文本帧必须是 hello（`proto` / `origin` / `serviceId` / `subscribe` /
  可选 `token`），且每会话只允许一次；CE 帧先于 hello 到达即
  `1002 hello expected`——否则任何能建连的客户端都能绕过准入直接注入事件。
- token 用常量时间比较；不匹配 `1008 unauthorized`；重复 hello `1002`；
  无法识别的帧 `1002`；帧处理异常 `1011`。
- hello 在准入检查**之后**才碰 `receive()`，`receive()` 只服务已准入的
  peer（客户端侧对称：ack 之前不投递 CE 帧）。服务端 ack 只回
  `proto` / `accept` / `origin` / `subscribe`。
- `subscribe` 元素是**前缀**；容忍 `{prefix, group}` 形式（`group` 目前不
  参与语义，只为向前兼容）。
- 出站拨号有握手看门狗：接受了 socket 却不回 hello 的 peer 会被 abort 并按
  指数退避（默认 1s 起、上限 30s）重连。
- **没有应用层心跳**：连接活性依赖 TCP/WS 层行为、出站 `send` 失败即摘连接、
  以及对端 close 的即时感知（设计里预留过 `keepalive` 键，未落地，
  `freeway.cloud.event.*` 中不存在这个键）。半开连接由握手看门狗兜住——
  socket 打开后 `event.handshake-timeout-ms` 内未完成 hello/ack 即中止；
  应用层心跳列为待定扩展（§8.2）。

**事件帧**：CloudEvents 1.0 JSON，`fwchannel`（`class`/`topic`）与 `fworigin`
两个扩展承载 Freeway 语义；`specversion` 不匹配即拒收。PEER 身份是
host+port；同一 origin 只保留一条连接，规则是**字典序小的一方发起的连接胜出**
（两侧同规则，同时拨号必然收敛到一条）。

**出站管道**：`EventBus` publish → `CloudEventSink` → 按 peer 的订阅前缀
过滤 → 逐连接发送。发送失败即丢弃该帧并触发重拨；**没有背压**（发布线程
直接扇出，慢 peer 不阻塞发布方）。

**入站管道**：`PeerHub.receive` → 拦截器（`CloudEventInterceptor`，可丢弃）
→ 渠道门禁 → `EventBus.publishInbound`。本地来源的事件（origin = 自己）
直接丢弃，避免网格回环。

| 渠道 | 门禁 |
|---|---|
| CLASS（按类名反序列化） | 白名单**默认拒绝**：未配置则不解析任何类，永不回落到"接受任意类型" |
| TOPIC（peer 自带 topic + payload） | 白名单为空 = 接受任意 topic；非空则按前缀匹配 |

入站节点若声明了订阅，启动时对松散姿态逐条告警：无 CLASS 白名单（该渠道
事件被丢弃）、无 TOPIC 白名单（接受任意 topic）、无 token（任何能连上的
peer 都可连）。

**去重**：事实的 id 由 bus 铸造并随帧携带；`EventBus` 的入站窗口按 id 去重，
由 `freeway.cloud.event.dedup.enabled` 打开（默认关，键与容量见
`docs/freeway-config.md`）。去重**不是拦截器**——它放在每个传输都要经过的
那一个漏斗（`publishInbound`）上，否则同一条事件经多条传输到达时会漏判。

**装配与关停**：`CloudEventModule` 显式安装；`freeway.cloud.event.enabled`
显式 true/false 优先，未设时"配了 peers"即为开启，什么都不设则模块保持
惰性（装模块本身不产生副作用）。停机时先摘 sink 再关连接，出站连接发
`1001 going away`，并留出极短的关帧窗口，不让容器关闭等一个已经走掉的 peer。

### 5.4 可观测性 observe

- `Tracer` 生成 traceId/spanId，经 `InvocationContext` 跨边界传播
  （异步用 `ContextExecutor`），MDC 只是显示层。
- 出站注入、入站提取 **W3C `traceparent`**，与本节点已有的 `tracestate`
  一起透传（`TraceContext.traceState`），与 OTel 互操作。
- 入站 span 由 `TracingFilter`（`HttpFilter`，order `-105`）创建，并显式把
  该 span 绑进 `InvocationContext`（`Tracer.Span.context()`）——span 只挂在
  环境层时会被作用域层遮蔽，这是曾经真实失效过的点。探活/指标路径
  （`/healthz`、`/health/live`、`/health/ready`、`/metrics`）不建 span。
- 入站 `PropagationFilter`（order `-110`）执行 extract → 合并 → `runWith`，
  并把入站 traceId 写入 MDC。
- `Metrics` 是 commons 的 SPI，`CloudObserveModule` 把模块自有的一个
  `MetricsDefault` 注册表**按三角色绑定**：具体类（单例）、`Metrics` SPI
  （primary，覆盖容器内置 `NoopMetrics`）、`/metrics` 路由读取的
  `MetricsSnapshot` 导出视图——三方永远指向同一注册表。`/metrics` 按
  Prometheus 文本格式输出（`text/plain; version=0.0.4`，手写零依赖），
  timer 输出 `_count` / `_seconds_total`。
- **诚实的能力边界**：目前只有 4 条**无标签**序列——`cloud.rpc.calls`、
  `cloud.rpc.failures`、`cloud.rpc.duration`、`tracer.span.duration`。没有
  labels、没有按服务归因、没有网格指标（§8.2）。
- 换导出后端 = 不装 `CloudObserveModule`，自行 primary 绑定 `Metrics` /
  `Tracer` 并提供导出路由（见 `CloudObserveModule` javadoc）。

### 5.5 韧性 resilience

三个 SPI，默认实现全用 JDK 并发原语：

- `CircuitBreakerDefault`：失败计数滑动窗口（默认 60s），超过
  `failure-threshold`（默认 5）转 OPEN；OPEN 持续 `open-window`（默认 30s）
  后放行**单个**半开探测，成功回 CLOSED、失败重开；成功重置失败窗口。
- `RateLimiterDefault`：令牌桶，burst 默认 1（严格速率）；**默认关闭**。
- `RetryerDefault`：指数退避 + **抖动**（base/2..base，避免重试同步）。

**默认在 `CloudHttpClient` 层统一生效**（最稳定、最容易落地的路径）：

```
rateLimiter.tryAcquire()
  → breaker.allowRequest()
  → 传输尝试： discovery.getInstances → loadBalancer.choose → 拼 URL
              → 注入 InvocationContext → httpClient.send
  → CloudResponse / CloudException
（重试从 rate-limit 步重新开始，每次尝试重新选实例）
```

- **本地拒绝语义**：circuit-open / rate-limited 是 retryable=false 的
  `CloudException`（限流重试会立即再失败），且计入 `cloud.rpc.failures`。
  限流先于熔断——本地拒绝不消耗半开探针名额。
- 派发期的非预期本地异常（坏 URL/头、discovery 后端缺陷）统一映射为
  `CloudException.dispatch`（retryable=false）：调用面单一，半开探针总有
  结局，熔断器不会卡在半开。
- 聚合开关 `freeway.cloud.rpc.resilience = auto | off`：`auto`（默认）由细项
  键治理；`off` 是显式总闸（NO_RETRY / NOOP / UNLIMITED，忽略全部细项键），
  是 mesh 接管与故障诊断的逃生口，启动期校验非法值即失败。
- 超时**不受**韧性治理，永远生效（`rpc.connect-timeout` /
  `rpc.request-timeout`）。
- 未装 `CloudResilienceModule` 时 client 退化到内置默认（3 次重试 / 100ms
  起退避 / 阈值 5 / 无限限流），默认值与配置层同源于 `CloudConfigKeys` 的
  `*_DEFAULT` 常量——一个数只有一个来源。
- `@Retry` / `@CircuitBreak` / `@RateLimit` 注解 + `Advisor` 织入**未交付**
  （§8.2）。

### 5.6 健康检查 health

`CloudHealthModule` 贡献两个**路径固定（不可配）**的 K8s 语义端点：

- `/health/live` —— 进程存活，固定 `{"status":"ok"}`。
- `/health/ready` —— 聚合 `CloudHealthContributor` 集合（contribute 模式），
  全健康 `200`，否则 `503`。

注册表就绪由 `RegistryHealthContributor`（随 `CloudDiscoveryModule` 交付）
提供，语义只声明框架**验证得到**的事实：

- 从未注册过（无 HTTP 模块、无服务声明）→ 报 `not registered`，而不是一句
  空洞的 healthy。
- 注册之后，**连续 3 次**心跳失败（renew 抛错，或存在性校验返回空）→ 不健康；
  任何一次成功即清零——单次抖动不该把 pod 摘出轮转。
- 关停期间（已反注册、只在收尾已接收的工作）→ 直接不健康，这正是把探测
  驱动的负载均衡器先摘出去的方式。

单独安装 health 模块时贡献者集合为空（恒 ok）。外部后端连通性检查由替换
注册/存储后端的适配器自备（未交付，§8.1）。

`freeway-http` 自身的 `/healthz`（`freeway.http.health.*`）是**另一套**端点：
http 探针在 `HttpModule`/`HealthFilter`，cloud 探针只在装
`CloudHealthModule` 时注册。两者语义独立，不要配成同一路径互相抢占。

### 5.7 密钥 secret

```java
public interface SecretStore {
    Optional<String> get(String key);
    default Optional<byte[]> getBytes(String key);
}
```

- **不提供 `asMap()`、不提供默认值**：密钥不可批量暴露，也必须显式配置。
  这是 API 级安全边界，也是它与配置系统分开的核心理由（暴露面、审计级别、
  轮换语义都不同）。
- 默认 `SecretStoreDefault` 的来源是 env（键大写、`.`→`_`）→ 密钥文件；
  外部后端（Vault/KMS）由适配器 `.primary()` 接入（未交付，§8.1）。
- 文件型存储**在 size 或文件全精度 mtime 变化时重读**（节流 1s）；读失败或
  文件瞬时消失时保留旧值并告警——契合 k8s projected volume 的原子换卷。
  SPI **没有 `reload()`**：远端后端的租约/TTL 由适配器自理。
- `SecretSymbolSource` 包装 `SecretStore` 参与 `SymbolSource` 解析，使
  `@Symbol("db.password")` 可解析密钥；优先级以 `order()=15` 声明（env 层 10
  与文件层 20 之间），**与模块安装顺序无关**。其自身配置
  （`freeway.cloud.secret.file` / `secret.keys`）刻意直接从系统属性读取——
  provider 参与符号解析，路径再走一遍会递归，因此这两个键仅 `-D` 生效。

### 5.8 对象存储 storage

`ObjectStorage`：`get` / `put` / `delete` / `list` / `presignedUrl`——**同步
API**，遵循 `Database`/`Pool` 模式，并发交给虚拟线程。

`ObjectStorageDefault` 是本地文件系统（`root/bucket/key`）：

- **路径安全**（对照 freeway-http staticfile 的回归要求）：bucket 校验（禁
  `..` 与分隔符）、key normalize 后禁绝对路径与 `..` 前缀、读写删路径均
  经 `toRealPath` 校验落在挂载根内。
- **写入走临时文件 + 原子替换**（`ATOMIC_MOVE`，降级 `REPLACE_EXISTING`）：
  目标处即使被植入 symlink，也是被替换为链接本身而非被跟随，检查与写入
  之间没有 TOCTOU 窗口。
- `list` 只列常规文件，并跳过指向根外的 symlink（与 `get` 的拒绝语义一致，
  避免泄露外部文件名）。
- `delete` 只在真实移除（symlink 或普通文件）时发 `ObjectDeletedEvent`；
  不存在的键是 no-op，**不发幽灵删除事件**。
- `presignedUrl` 在本地无签名语义，返回 empty；`put` 返回 etag（SHA-256），
  **不返回 versionId**：接口没有按版本寻址的读/删，版本标识只能是调用方
  打印一下就扔掉的值——版本能力与其可用的操作一起排期。
- 领域事件 `ObjectStoredEvent` / `ObjectDeletedEvent` 经 `EventBus` 发布。
  与主链路（discovery/rpc/observe/resilience）解耦，可独立安装。

### 5.9 服务间安全

范围边界：**service-to-service 云原生安全**，不是应用级登录/会话框架。

- **传输加密（mTLS）**：`TransportSecurity`（`sslContext()`，默认常量
  `NONE` 即开发态明文）。core 用 JDK `SSLContext` + 文件型
  keystore/truststore（PKCS12/JKS，键见 `docs/freeway-config.md`）：
  `CloudRpcModule` 按键解析——keystore 为空 → `NONE`，否则构建
  `TransportSecurityDefault`。**同一条出站安全面同时服务 RPC（`https://`）
  与事件网格（`wss://`）**：网格不是第二个配置面。能力由运行时配置决定，
  静态 marker 表达不了条件能力，故 core 不预铺 `@Mtls` / `@None`；自定义
  实现（Vault 动态证书等）直接 bind 即可。
- **身份传播**：`PrincipalContext` 经 `InvocationContext` + `AuthPropagator`
  注入/提取 `x-principal` / `x-principal-roles`，与 `traceparent` 走同一管线。
  传播的是**已验证身份**，不是原始凭据。
  **信任边界**：入站提取信任传播头，**默认关闭**
  （`freeway.cloud.auth.extract.enabled=false`），只在可信服务网内显式开启；
  出站注入恒开（只转发本地已验证身份）。生产 token 校验（JWT/Opaque）是
  自定义安全模块的职责，core 不内置。
- **网格准入**：`freeway.cloud.event.token` 常量时间比较，作用在 hello 上。
  配置了 token 却用明文 `ws://` 拨号时**启动告警而非拒绝**——在 sidecar 里
  终止 TLS 是正常部署，此时 token 由网格自身的 mTLS 保护。
- **服务端鉴权**回归应用自身的 Route/Filter：框架不为 `/rpc/*`、`/metrics`、
  `/health/*` 内置认证，部署面应把它们当内部端点。
- **密钥源**：§5.7 的 `SecretStore`（`order()=15`）。

## 6. 配置键总览

**键的语义、默认值与示例一律见 `docs/freeway-config.md` §五（Cloud）**。本节
只固定读键的方式与三种形态，避免同一份清单在两个地方漂移。

- **读取时机**：cloud 的键在启动期解析（各 hook 的 `start`），此后不再重读。
  运行期真正需要热更新的值走 boot 的配置级联；密钥文件是唯一的例外
  （§5.7）。
- **形态一：行为键**——普通值（超时、阈值、地址、白名单、token）。有默认值，
  默认值以 `CloudConfigKeys` 的 `*_DEFAULT` 常量为唯一来源，模块配置层与
  库级 fallback 共享。**布尔键一律走容器 `Coercer`**（接受 `true/false`、
  `yes/no`、`on/off`、`1/0`），无法识别的值启动失败并点名键与值——静默变
  `false` 会让 `auth.extract.enabled` 这类开关被悄悄关掉。
- **形态一之特例：`auto`**——值必须推导、写不出来时才用它，且推导结果必须在
  启动日志里可见（`registry.service-scheme` / `service-host` /
  `shutdown-drain`，§5.1）。给常量默认值加 `auto` 只是同一规则的第二种说法，
  不做。
- **形态二：声明键**——`freeway.cloud.secret/discovery/registry/storage.type`。
  它们**不选择实现**：框架不做类名反射加载或类路径扫描，真正选择实现的是
  `.primary()` 绑定、`@Local` 标记与装了哪个适配器模块。仍用本地实现而类型键
  非空且非 `local` 时，`BackendTypeGuard` 启动告警一次。
- **形态三：存在性开关**——少数键由"是否出现"决定行为，最典型的是
  `freeway.cloud.event.enabled`：显式 `true`/`false` 优先，未设时"配了
  `event.peers`"即开启，都不设则模块惰性。
- `freeway.cloud.rpc.resilience = auto | off` 是唯一的**聚合键**：`off` 是
  逃生口，启动期校验非法值即失败。
- `freeway.cloud.secret.file` / `secret.keys` 只认 `-D`（§5.7）。

## 7. 明确不做

排除项与理由（前四条是**结构性**的，不会因为"以后可能需要"而改变）：

- **透明的本地优先调用**（"本地没人接就悄悄上网"）：调用是本地还是远端写在
  组合（绑定）里。曾有一版 `CallBus`（topic 寻址的调用总线 + 本地未命中自动
  跨进程）与配套的 `CallBridge` / `RemoteProxyFactory.localFirst()`，因概念
  膨胀且把"调用去哪儿"藏进运行期而整体删除，`freeway-ioc` 里也没有为它准备
  的桥接缝。
- **注解路由 / `@CloudClient` 透明 bean / `CloudExporter` 自动导出 / `@CloudEvent`
  注解实体**：导出、调用与事件路由都是显式声明，不往业务类型上挂路由注解，
  也不做注解驱动的自动注册。
- **二进制/多路复用协议（gRPC 桥）**：JSON over HTTP/1.1|2 已够；等 profile
  数据说不够再做。
- **业务数据进入 `InvocationContext`、实例属性进入 `@Marker`**：前者只装
  基础设施/安全/应用三类 KV，后者是静态类型，运行期属性走
  `ServiceInstance.metadata`。
- **分布式事务 / saga 补偿 / 分布式锁**：事务内联失效是不可消除的事实，
  框架不兜底。需要最终一致性的写操作走 `EventBus`（outbox），不走 RPC。
- **按 capability 发现**（"谁导出了 mapping X"）：地址是 `serviceId`，由消费
  方在组合里点名；扩展发现面需要每个适配器实现新的查询契约，收益不抵成本。
- **泛型返回类型的运行期重建**：`Class<T>` 单层覆盖当前全部内部用例；需要
  复杂泛型时应用 sealed result 类型。
- **事实反向打进调用通道**：fact 与 question 的语法闸门已从源头隔离。
- **classpath 扫描式自动注册**（`ServiceLoader` 除外，可关）。
- **第三方 SDK 进入 core**：任何需要第三方客户端的实现都是 ext 适配器。
- **入口流量治理 / 集群调度 / 证书平台**：K8s Service、Ingress、网关、
  Serverless、分布式调度属基础设施，不作为 core 能力。
- **事件网格承载 MQ 语义**：跨节点是至多一次——不做 ACK/重投/死信/延迟投递/
  `qos` 透传/显式事件事务批，帧里也不放框架兑现不了的位。要可靠投递用 MQ
  （ext 的 `freeway-mq-kafka`）；"随 DB 事务缓冲"已由 `Defer` 覆盖。
- **网格内的全局成员视图**：成员是"连接即事实"，不做分布式注册表或一致性
  成员表；订阅匹配只做前缀，不实现 CNCF subscription 表达式规范——前缀够用
  即止。
- **webhook / HTTP 出站通道**：对第三方系统的集成由应用层订阅后自行出站；
  节点间投递不用 HTTP——它没有连接语义，也就没有"对端走了"这个事实。
- **按版本寻址的对象存储（versionId）**：接口没有版本读/删，版本标识只能是
  调用方打印一下就扔掉的值（见 §5.8）。

## 8. 路线图

两个档位：**未交付（无排期）** 是"core 已经给了接缝，等真实需求驱动"；
**已评估、待排期** 是 core 自己欠的收口。每项都写了为什么值得做与最小的
收口动作——不做也可以，但要知道欠的是什么。

### 8.1 未交付（无排期）

全部属于 ext（独立仓库），接入协议见 §3.3：绑定对应接口并 `.primary()`，
必要时自带后端 marker 与类型键消费，必须是完整实现而非占位。

| 能力 | 接缝 | 最小收口动作 |
|---|---|---|
| 注册 / 发现后端（Nacos、Consul、K8s endpoints） | `ServiceRegistry` + `ServiceDiscovery` | 一个适配器模块 + 真实后端测试；`renew` 已返回 boolean，自愈契约现成 |
| 密钥后端（Vault、KMS） | `SecretStore` | 适配器自理租约/TTL（SPI 无 `reload()`），文件轮换语义已定 |
| 对象存储后端（S3 兼容） | `ObjectStorage` | 版本能力与版本寻址的读/删一起排期，不要只交一半 |
| 指标导出（OTel/Prometheus push） | 不装 `CloudObserveModule`，primary 绑定 `Metrics` + 自备导出路由 | 顺带把 §8.2 的标签维度一起设计 |
| trace 导出（OTLP） | 同上（`Tracer`） | `tracestate` 已透传，`TraceContext` 够用 |
| Kafka 事件桥 | `EventSink`（`freeway-mq-kafka` 已在 ext） | 可靠投递走它，不是网格 |

### 8.2 已评估、待排期

| 项 | 为什么值得做 | 最小的收口动作 |
|---|---|---|
| 指标维度（labels）+ 按服务归因 | 现在 4 条无标签序列，一个失败没法回答"哪个服务" | `Metrics` 加一个可选的 tags 参数（不破坏现有调用面），先给 `cloud.rpc.*` 打 `service` 标签 |
| retry budget | 只按调用重试，重试风暴在依赖大面积失败时反而加压 | `Retryer` 增加按服务的令牌预算，超额直接失败而不是继续退避 |
| zone / weight / canary 默认策略 | metadata 已有，但默认 round-robin 完全不读，"标了没用" | `LoadBalancerDefault` 在 metadata 存在时优先按 zone/weight，无 metadata 时保持 round-robin |
| 网格出站背压 + 网格指标 | 目前发布线程直接扇出：慢 peer 不阻塞发布方，代价是丢帧无声 | 每连接有界队列 + 丢弃计数指标；与上一项共用标签机制 |
| 网格应用层心跳 | 半开连接只能靠 TCP/WS 行为与 send 失败发现，被 NAT/LB 静默丢弃时不及时 | 可选的 `event.keepalive` 间隔键（默认关）＋ ping/pong 帧与失联判定；键名在设计里已预留 |
| readiness 发布到注册表 + startup probe | `Health.starting()` 已存在却没人发布：注册即"就绪"对外是假话，慢启动 pod 会被打 | SPI 加一个就绪位写入（或扩展 `register` 的可选就绪态），注册 hook 先发 starting、就绪后再翻转；同时补 startup probe 语义 |
| 出站证书轮换 | `SecretStore` 能轮换，但 TLS 材料只在启动时读一次——证书到期只能重启 | `TransportSecurityDefault` 复用密钥文件的 size/mtime 监测，重建 `SSLContext` |
| 每方法韧性策略 | 现在整个 `CloudHttpClient` 一套阈值，读与写该有不同的重试观 | `@Idempotent` 同级的每方法声明（超时/重试上限），由 `RemoteProxyFactory` 读取 |
| 本地调用的韧性注解（`@Retry` / `@CircuitBreak` / `@RateLimit` + `Advisor` 织入） | 三个 SPI 目前只在 `CloudHttpClient` 出站路径统一生效（§5.5）：进程内直连的慢依赖没有同一套治理，应用得手写模板代码 | 复用 ioc 已有的 `Binding.advise` / `Advisor.wrap(selector, advice)`，读方法级（退到类级）注解并复用同一批 SPI；注解只做选择，阈值仍是现有键，不新增第二套配置 |
| SKILL 文档 cloud 章节（`docs/SKILL.zh.md`） | 应用面 API 仍在动，写早了立刻过期 | 以 `docs/DEVELOPER-GUIDE.md` 的 `## Cloud` 一节为骨架（三档接入表 + 能力索引表），不另起一套说法 |

### 8.3 交付标准（沿用实施计划）

无第三方云 SDK 也能运行；核心抽象都有本地默认实现；RPC、观测、韧性、健康
能各自独立工作且装配可选；`freeway-ext` 只负责第三方适配与增强，不影响 core
的可运行性与默认语义；能在 core 内闭环的优先在 core 完成，依赖特定云平台或
基础设施的进 ext，入口流量治理/集群调度/证书平台不纳入 core；所有能力都有
回归测试覆盖。回归覆盖按边界补：模块装配与默认实现解析、注册/发现/注销/心跳
驱逐、远程调用的成功/超时/失败/重试换实例、trace 传播、指标输出、熔断/限流/
重试、安全传播、live/ready 探针、对象存储读写。

## 9. 修订记录

| 时间 | 变更 |
|---|---|
| 2026-08-19 | 总设计定稿（Phase 0–7 落地）：核心对象 + 本地默认、注册发现与远程调用、可观测性、韧性、安全、对象存储；取代早期 design-A/design-B 两套并行方案 |
| 2026-08-27 | RPC v1：`RemoteCaller` + `RemoteInvocationException` + `RemoteProxyFactory`（本地优先/纯远端双模式）+ `RpcEndpoint.of(...)`；`callAsync` 与每调用超时端到端接线 |
| 2026-08-28/29 | 事件网格修订：帧内时间戳 `fwtimes` 删除（改用 CloudEvents `time`）；去重从"拦截器"移到 `EventBus.publishInbound` 这个唯一漏斗 |
| 2026-09-03 | 配置中心整体删除（`CloudConfig` / `ConfigRef` / `ConfigSubscription` / `config/` 包）：配置级联与热重载统一归 boot（`AppConfigDefault` / `freeway.config.file`），cloud 只贡献 `SecretSymbolSource`（order 15） |
| 2026-09-04 | 文档收口：默认实现在功能包（不在 `internal/`）、`choose(instances)` 单参签名、health 端点路径固定不可配、core/ext 边界按"ext 目前不含任何 cloud 适配器"改写 |
| 2026-09-12 | RPC v2：导出改为**申报式**（`RpcExport` 数据贡献 + 一条 `/rpc/{mapping}/{method}` 通配路由 + `.before(HTTP_SERVER)` 装配 hook），`RemoteCaller` 由框架绑定，独立组装改用 `RpcEndpoint.route(...)`；同日 v3 曾设计 `CallBridge` 让 `CallBus` 本地未命中时自动跨进程，**定稿未实施即撤回** |
| 2026-09-12 | RPC v4：**删除 `CallBus`** 及其卫星类型；`RpcEndpoint` 直接调用容器解析出的 handler（`RpcTarget` 方法表，重载在启动期失败）；`RemoteProxyFactory` 收敛为纯远端；文档由 `freeway-remote-callbus-design.md` 更名 |
| 2026-09-12 | 审计轮次：失败获得 `kind()`（13 值）；`ServiceRegistry.renew` 返回 boolean 并自愈；readiness 只说框架验证得到的事（3 次失败判不健康、未注册报 `not registered`、关停期不健康）；密钥按 size + 全精度 mtime 轮换；网格出站 TLS 与 RPC 共用一条安全面，明文 + token 启动告警；停机加 `registry.shutdown-drain` / `rpc.shutdown-grace` 与 WS `1001 going away`；contribution id 统一 `freeway.cloud.*`；`@Local` 收敛到参数/字段并覆盖 `Metrics` / `TransportSecurity`；补充可选输入规则与 `Wiring` 兼容构造 |
| 2026-09-13 | 模块组合改树：`ModuleEx.subModules()` 与 `ModuleTree` 删除，组合成为入口构建的 `ModuleNode` 值（容器持有，`Container.moduleTree()`）；伞模块 `CloudModule` 改为片段工厂 `CloudModules.standard()`，应用可替换或取出其中任一模块 |
| 2026-09-12 | 配置面审计：`registry.service-scheme` / `service-host` / `shutdown-drain` 默认改为 `auto`（分别跟随 HTTP 服务器 TLS、推导可路由地址、由注册表后端回答），网格拨号方案改读解析出的实例端点；布尔键统一 `Coercer` 解析（垃圾值启动失败，不再静默 `false`）；override 文件重复键启动告警点名两个文件；新增 `WebServer.secure()` 与 `ServiceRegistry.drainWindow()` |
| 2026-09-12 | 文档合并：本文取代 `freeway-cloud-unified-design.md` / `freeway-cloud-events-design.md` / `freeway-cloud-rpc-design.md` / `freeway-cloud-implementation-plan.md`；四份文档仍然有效的排除项与能力边界（无应用层心跳、MQ 语义、全局成员视图、webhook 出站、`@CloudEvent` 注解实体）与 core 后续项（含 `Advisor` 织入、网格心跳）并入 §5 / §7 / §8；配置键清单移出为对 `docs/freeway-config.md` 的索引 |
