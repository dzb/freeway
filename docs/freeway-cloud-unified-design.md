# freeway-cloud 完整设计（定稿）

> **状态：设计基线（2026-08-19；2026-09-03/09-04 维护说明）**。本文档是 freeway-cloud 的设计基线，
> 取代早期并行的 design-A（路径级 RpcClient 线）与 design-B（方法级 RPC
> 线）两套方案（相关早期文档已移除）。
>
> **2026-09-03 维护说明**：1.4.0 起配置级联与热重载统一归
> `freeway-boot`（`AppConfigDefault` / `freeway.config.file`），cloud 的
> `config/` 包与 `CloudConfig*` API 已删除（§3.5/§5.3/§7 已按此改写）；
> CloudEventBus 与 CallBus 远程桥分别以
> `freeway-cloud-events-design.md`、`freeway-remote-callbus-design.md`
> 为最新边界。云模块当前结构以源码、`docs/freeway-config.md` 与
> `docs/DEVELOPER-GUIDE.md` 为准。
>
> **2026-09-04 维护说明**：文档收口——「默认实现在 `internal/`」、
> 默认类名（`TransportSecurity.NONE` 常量 / `TransportSecurityImpl`）、
> `choose(instances)` 单参签名、health 端点固定路径（不可配）、§7 配置键
> 清单与 §8/§12.1 的 core/ext 边界均已与源码对齐；freeway-ext 目前不含
> 任何 cloud 适配器，本文不再以「ext 提供 X」声称未交付能力。
>
> **本定稿的关键决策（相对早期文档的收敛）**：
>
> 1. **远程调用模型**：显式 HTTP 调用（`CloudHttpClient`，包 JDK
>    `HttpClient`），**不做方法级 RPC**——不引入 `@CloudClient` 接口代理、
>    `CloudExporter` 服务端导出、`/rpc/{id}/{method}` 私有协议。被调方是
>    普通 Freeway HTTP 应用，零 cloud 代码。
> 2. **命名统一**：接口裸领域名 + 默认实现 `XDefault`（AGENTS.md 规则）。
> 3. **依赖定稿**：`freeway-cloud` compile 依赖 `freeway-http`（core 模块，
>    零外部依赖，单向依赖无环）。
> 4. **吸收 design-A**：`ServiceDeclaration` 扩展点、`CloudConfigKeys`
>    完整配置键清单、`SecretStore` 独立子系统（API 级安全边界）。
> 5. **事实修正**：`install()` 按模块实例身份去重（非 class）→ 伞模块与
>    子模块不可混装；`Coercer` 无三参重载 → metadata accessor 用两参 +
>    默认值短路。详见 §11。

## 1. 总原则

- `freeway-cloud` 是核心模块，零第三方依赖（SLF4J 除外）。
- 接口在 core、可替换默认实现（`XDefault`）在各自功能包（`internal/`
  只放非公开、无稳定性承诺的实现细节），IoC 接线集中在 `*Module`。
  云后端替代实现经 `.primary()` 绑定接入——freeway-ext 目前不含任何
  cloud 适配器（见 §8）。
- 无类路径扫描、无字节码织入、无透明远程 bean。
- `.primary()` 让替代/扩展绑定覆盖默认（引擎/后端选择）；本地内置
  默认经 `.marker(Local.class)` 标记为 `@Local` 后端（§6.1，静态、无值）。
- `RuntimeHook` 负责生命周期：注册/反注册显式、可排序、可测试。
- 默认实现必须生产级（质量 bar：`PoolDefault`/`JsonCodec`），非占位桩。

## 2. 模块与依赖

```
freeway-cloud  (com.jujin.freeway.cloud)
  依赖：freeway-commons, freeway-ioc, freeway-boot, freeway-http (compile)
       + slf4j-api
  JDK 25：虚拟线程, ScopedValue, java.net.http.HttpClient,
          SSLContext/KeyStore, WatchService, UUID
```

freeway-http 的用途：提供 WebServer/路由面——cloud 在其上贡献
`/health/live|ready`、`/metrics` Route 与 `PropagationFilter`
（`HttpFilter` 扩展点），CloudEventModule 的 WS 端点亦跑在 HTTP 端口上。
依赖方向单向（cloud→http），http 零外部依赖，无传递负担。只装 events
等子模块的用户在 classpath 上多一个 core jar，无运行时开销。

## 3. 核心对象（4 个）

| 对象 | 职责 |
|---|---|
| `serviceId`（普通字符串，无公开类型） | 是谁 |
| `ServiceInstance` | 有哪些可用实例 |
| `Endpoint` | 怎么到达 |
| `InvocationContext` | 跨边界传播什么 |

（早期第五个对象 `CloudConfig`——"运行时配置从哪里来"——已于 1.4.0
删除，配置源归 boot 配置级联，见 §3.5。）

### 3.1 ServiceId —— 统一服务身份（普通字符串）

serviceId 是**普通字符串**，不设计为公开类型（遵循 CLAUDE.md：
"ServiceId is intentionally not a public type"——服务 id 就是字符串，
由 ioc `ServiceIds.normalize` 内部归一化：trim + 非空守卫）。

- 本地容器绑定 id 与远程发现逻辑名共用同一名字空间：绑定 id 经
  `Binding.id()` 上的 `ServiceIds.normalize` 隐式守卫；`CloudHttpClient`
  调用入口（`call(String serviceId, ...)`）对同一字符串做非空校验，
  两作用域语义一致，cloud 不另造归一化逻辑。
- `ServiceInstance.serviceId()` 是 String；`Container.get(type, id)` 取
  本地绑定，`CloudHttpClient.call(serviceId, ...)` 定位远程实例——同一个
  字符串，无转换、无包装类型。

### 3.2 Endpoint —— 结构化定位符

```java
public record Endpoint(String scheme, String host, int port, String basePath) {
    public URI uri() { ... }
}
```

覆盖 scheme/port/basePath、DNS 名、K8s Service FQDN、mesh sidecar、非 IP
定位。不做裸字符串拼接。basePath 构造期规范化（空 → `""`、补 `/` 前缀、
去尾斜杠），整个元组在构造期校验能否渲染为合法 URI——坏定位符在装配期
报错，绝不留到请求线程。IPv6 字面量按 RFC 3986 加方括号（容忍已带括号
的入参）。

### 3.3 ServiceInstance —— 实例模型

```java
public record ServiceInstance(
    String             serviceId,    // 逻辑身份（普通字符串，见 §3.1）
    String             instanceId,   // 实例级稳定身份，与位置解耦
    Endpoint           endpoint,     // 结构化定位符
    Map<String,String> metadata      // zone/version/weight/canary/... 自由袋
) {}
```

- `instanceId` 稳定：容器重调度换 IP/端口仍是同一实例，仅更新 `endpoint`。
- metadata 是自由袋；读取走类型化 accessor + 默认值（两参 `Coercer` +
  默认值短路，见 §11-2），如 `weight()`/`zone()`/`version()`/`isCanary()`。
- **健康态不放入 `ServiceInstance`**：由 `Health(live, ready, lastSeen)`
  单独维护（发现层/注册中心），`LoadBalancer` 只选 `live && ready`，
  `lastSeen` 超时驱逐（stale eviction）。注册/续约/驱逐见 §5.1。
- canary/zone/weight 路由是 `LoadBalancer` + metadata 的职责，实例只给数据。

### 3.4 InvocationContext —— 跨边界传播载体

```java
public final class InvocationContext {
    // ScopedValue 槽；只含三类子上下文
    TraceContext     trace;      // traceId/spanId —— 基础设施拥有
    PrincipalContext principal;  // 已验证身份 —— 安全拥有，不可伪造
    Baggage          baggage;    // KV —— 应用拥有
}
```

- 不承载业务数据、配置快照、对象缓存。
- 传播统一由 `Propagator` 处理：`extract(headers)`（返回部分上下文，
  由 `PropagationFilter` 按"非空胜出"合并）/ `inject(ctx, headers)`。
  内置 `TracePropagator`（W3C `traceparent`：`00-traceid-spanid-flags`）、
  `AuthPropagator`（`x-principal` / `x-principal-roles`，只传播已验证
  身份，见 §5.9）、`BaggagePropagator`（W3C `baggage`，键值百分号编码，
  任意值无损往返）。新增关注点 = 贡献一个 `Propagator`，不改 core。
- 两个边界应用点：`PropagationFilter`（`HttpFilter`，入站
  `extract` → 合并 → `InvocationContext.runWith` 绑定请求作用域）与
  `CloudHttpClientDefault`（出站注入当前 ic；`callAsync` 在派发前捕获
  调用方上下文，虚拟线程上以 `runWith` 恢复）。
- 进程内传播：`ScopedValue` 承载；异步跨线程用 `ContextExecutor`
  （freeway-commons）显式传播。**MDC 只作显示层**（`JULMDCAdapter` 是
  ThreadLocal 型、不跨线程、虚拟线程终止即清理），上下文载体必须是
  `ScopedValue`。

### 3.5 CloudConfig —— 运行期动态配置源（已删除）

**已删除（2026-09-03 维护说明）**：1.4.0 起配置级联与热重载统一归
`freeway-boot`（`AppConfigDefault` / `freeway.config.file`），cloud 的
`config/` 包与 `CloudConfig` 等 API 已移除。运行期动态键走 boot 的
`SymbolProvider` 顺序链（CLI 0 > 系统属性 5 > env 10 > cloud 密钥源
15 > 文件 20），cloud 只贡献密钥 provider（§5.4）。键与文档见
`docs/freeway-config.md`。

## 4. 包结构与装配

```
com.jujin.freeway.cloud
├── CloudConfigKeys.java / CloudModule.java / CloudHooks.java
│                              集中配置键（§7）/ 伞模块 / hook 名常量
├── annotation/                @Local —— core 唯一装配引用的标记（§6.1）
├── context/                   CloudContextModule; InvocationContext,
│                              TraceContext, PrincipalContext, Baggage,
│                              Propagator
├── secret/                    CloudSecretModule; SecretStore(+Default),
│                              SecretSymbolSource（§5.4）
├── discovery/                 CloudDiscoveryModule; ServiceInstance,
│                              Endpoint, Health, ServiceDiscovery(+Default),
│                              ServiceRegistry(+Default), ServiceDeclaration,
│                              LoadBalancer(+Default)（§5.1）
├── rpc/                       CloudRpcModule; CloudHttpClient(+Default),
│                              CloudRequest, CloudResponse, CloudException,
│                              TransportSecurity(+Impl); CallBus 远端桥接
│                              RemoteCaller / RemoteProxyFactory /
│                              RpcEndpoint（见 freeway-remote-callbus-design.md）
├── observe/                   CloudObserveModule; Tracer(+Default),
│                              MetricsDefault, MetricsSnapshot（§5.5）
├── resilience/                CloudResilienceModule; CircuitBreaker(+Default),
│                              RateLimiter(+Default), Retryer(+Default)（§5.6）
├── health/                    CloudHealthModule, CloudHealthContributor,
│                              HealthResult（§5.7）
├── storage/                   CloudStorageModule; ObjectStorage(+Default) 等
│                              （可选，§5.8）
├── events/                    CloudEventModule 等 —— 可选 add-on，伞模块
│                              不聚合安装（freeway-cloud-events-design.md）
└── internal/                  非公开、无稳定性承诺的实现细节（内置
                               Propagator、PropagationFilter/ReadyHandler/
                               MetricsHandler、RegistryStore、BackendTypeGuard
                               等）——不是默认实现的仓库
```

- `XDefault` 默认实现位于各自功能包（`discovery/` `rpc/` `observe/`
  `resilience/` `secret/` `storage/`），是可替换扩展点（CLAUDE.md 命名
  规则）；`internal/` 不含默认实现。`config/` 包已于 1.4.0 删除（§3.5）。

### 4.1 装配方式

- 每个子系统一个 `*Module`（只负责本子系统 IoC 接线）；`CloudModule`
  聚合安装全部子模块。接口与 record 不依赖 `Container`。
- **事实修正（重要）**：`install()` 按模块**实例身份**去重
  （`ContainerImpl` 用 `IdentityHashMap`），不是按 class。`CloudModule`
  内部 `new` 的子模块与用户单独 `new` 的子模块是两个实例 → 重复绑定 →
  `BindingIndex` 类型解析歧义。**伞模块与子模块不可混装**：用户要么装
  `CloudModule`，要么按需安装子模块集合，二选一。文档不再承诺"可安全混用"。

```java
@Marker(Builtin.class)
public final class CloudModule implements ModuleEx {
    @Override
    public void bind(Binder b) {
        b.install(new CloudContextModule());
        b.install(new CloudSecretModule());
        b.install(new CloudDiscoveryModule());
        b.install(new CloudRpcModule());
        b.install(new CloudObserveModule());
        b.install(new CloudResilienceModule());
        b.install(new CloudHealthModule());
        b.install(new CloudStorageModule());
    }
}
```
（`CloudEventModule` 是可选 add-on，不在伞模块内——需要 WS 事件网格时
显式安装。）

## 5. 能力设计

### 5.1 注册发现（discovery）

接口职责固定：

- `ServiceRegistry`：register / renew / deregister（生命周期）
- `ServiceDiscovery`：getInstances（查询；另有无次序的 `getInstance`
  default 便捷方法）
- `ServiceDeclaration`（吸收 design-A）：扩展点——任何模块声明
  "本次启动要注册什么端点"（HTTP/gRPC/自定义协议/多端口），
  `CloudDiscoveryModule` 的注册 Hook 统一收集注册：

```java
@FunctionalInterface
public interface ServiceDeclaration {
    /** HTTP server 启动后调用（host:port 已确定）。 */
    ServiceInstance resolve(Container container);
}
```

- `LoadBalancer`：策略接口（单参 `choose(List<ServiceInstance>)`，返回
  `Optional`），只负责 RPC 出站调用前的实例选择，不负责集群入口流量
  调度（K8s Service/Ingress/网关属基础设施）。core 内置
  `LoadBalancerDefault`（round-robin，跨虚拟线程安全）；random/weighted/
  zone-aware 等策略由应用/适配器直接 bind primary 实现（接口是
  `@FunctionalInterface`，无需注解，§6.1），策略输入
  （zone/weight/canary）经 `ServiceInstance.metadata()` 的类型化
  accessor 读取。
- 默认实现（各功能包，`@Local` 标记）：`ServiceDiscoveryDefault` /
  `ServiceRegistryDefault` —— 进程内注册表（RegistryStore），
  `getInstances` 只返回 `live && ready` 且未过期的实例（lastSeen 惰性
  驱逐），生产可用（单进程/静态拓扑场景）。跨进程动态发现需要外部注册
  后端：替代实现经 `.primary()` 绑定接入（freeway-ext 目前未交付，
  见 §8）。
- 注册生命周期：`RuntimeHook.start` 注册 + 周期心跳 `renew()`；
  `RuntimeHook.stop` 反注册（先摘流量再关服务器，见 §6.2）。
- serviceId 来源：`freeway.cloud.registry.service-id` →
  `freeway.app.name`。注册地址注意 0.0.0.0 绑定场景，由
  `registry.service-host` 显式覆盖（K8s 注入 POD_IP）。
- 静态第三方服务（PostgreSQL/Redis）由配置指定地址，不走 discovery。

### 5.2 远程调用（rpc）—— 显式 HTTP，非方法级 RPC

```java
public interface CloudHttpClient {
    CloudResponse call(String serviceId, CloudRequest request) throws CloudException;
    // callAsync(serviceId, request[, deadline]) —— 同一韧性编排，
    // 仅最后一段 socket 等待离开调用线程（Default 用虚拟线程覆盖）
}

public record CloudRequest(String method, String path, Map<String,String> headers, byte[] body) {
    static CloudRequest get(String path);
    static CloudRequest post(String path, byte[] body, String contentType);
    static CloudRequest post(String path, String jsonBody);   // Content-Type: application/json
}

public record CloudResponse(int status, Map<String,List<String>> headers, byte[] body) {
    <T> T bodyAs(Class<T> type, JsonCodec codec);
    boolean is2xx();
    String bodyAsString();
}
```

**调用链**（`CloudHttpClientDefault`，`rpc/`，包 JDK `HttpClient`；韧性
状态机在 `rpc/` 的 `ResiliencePolicy` 中实现，client 只解析每
serviceId 的熔断/限流分片并提供单次传输尝试）：

```
rateLimiter.tryAcquire()
  → breaker.allowRequest()
  → 传输尝试（transport attempt: 一个 discovery/choose/send）
      → discovery.getInstances(serviceId)   // 只返回 live && ready
      → loadBalancer.choose(instances)
      → endpoint + path 拼 URL
      → 注入 InvocationContext（traceparent / principal / baggage 头）
      → httpClient.send(...)                 // 虚拟线程同步阻塞
      → CloudResponse
```
（重试从 rate-limit 步重新开始；每次尝试重选实例。熔断半开期间的任何
失败——包括本地拒绝——都结算探针结局，保证熔断器不会卡在半开。）

- **被调方零要求**：就是普通 Freeway HTTP 应用，Route 照常贡献。
  无 `/rpc/*` 私有协议、无方法级派发、无跨边界异常序列化。
- 重试**必须重新选实例**（换 discovery 刷新后的不同实例），不 hammer
  死实例。连接失败/超时 retryable；**5xx 抛 `CloudException`（status>=500，
  retryable）进重试+熔断统计；4xx 作为响应返回**（调用方拥有 body，
  不重试）。`CloudException` 携带 retryable 标志。
- 超时：每调用 `HttpRequest.timeout(Duration)`，键
  `freeway.cloud.rpc.connect-timeout` / `request-timeout`。
- 默认：`bind(CloudHttpClient)` → `CloudHttpClientDefault`，标记
  `@Local`；替代传输（WebClient/gRPC/...）= 自定义实现直接 bind
  `.primary()`（freeway-ext 无此类适配器交付，core 不预铺 marker）。
- 响应侧 `bodyAs(Class, JsonCodec)` 与服务端用同一 `JsonCodec`
  （record/泛型/java.time 支持已验证）。
- **明确不做**：`@CloudClient` 接口代理、`CloudExporter` 服务端导出、
  透明远程 bean、方法名→路径映射。类型安全收益由应用层小封装获得
  （`bodyAs(Class)` 泛型返回已支持），不引入框架级代理与派发协议。

### 5.3 配置中心（config）——已删除

本节描述的 `CloudConfig` / `ConfigRef` / `ConfigSubscription` /
`CloudConfigDefault`（WatchService）与 `config/` 包在 1.4.0 起已整体
删除（见头部维护说明与 §3.5）：配置文件归 boot 的应用配置级联
（`application*.properties` 等，WatchService 热重载、`freeway.config.file`
扩展），运行期动态键经 boot 的 `SymbolProvider` 顺序链解析，cloud 在链上
只贡献 `SecretSymbolSource`（order 15，见 §5.4）。相关键、语义与文档见
`docs/freeway-config.md`。

### 5.4 密钥（secret）—— 独立于 config

| 维度 | SecretStore（现存） | CloudConfig（已删，§3.5） |
|---|---|---|
| `asMap()` | **禁止**（密钥不可批量暴露） | — |
| 缓存策略 | 启动读入 + 显式 `reload()`；轮换 TTL 属适配器职责 | — |
| 默认值 | **禁止**（密钥必须显式配置） | — |
| 适配器来源 | 本地：env（键大写、`.`→`_`）→ 密钥文件；外部后端由自定义 `.primary()` 接入，freeway-ext 未交付（§8） | — |

```java
public interface SecretStore {
    Optional<String> get(String key);
    default Optional<byte[]> getBytes(String key) { ... }
}
```

- `SecretSymbolSource`（`secret/`）包装 `SecretStore` 参与 `SymbolSource`
  解析，使 `@Symbol("db.password")` 可解析密钥。优先级以 `order()=15`
  声明（env 层 10 与文件层 20 之间）——**与模块安装顺序无关**。
  自身配置（`secret.file` / `secret.keys`）直接从系统属性读取，刻意不走
  `SymbolSource`（provider 参与符号解析，路径再走会递归）——两个键
  仅 `-D` 生效（docs/freeway-config.md）。
- 默认 `SecretStoreDefault`：env/file；外部后端（Vault/KMS 等）由适配器
  自定义绑定接入——freeway-ext 目前未交付。
- 独立理由（API 级安全边界，非实现差异）：`asMap()` 暴露面、审计级别、
  轮换语义与配置不同，合并会模糊边界。

### 5.5 可观测性（observe）

- `Tracer`/`TraceContext`：生成 traceId/spanId，`ScopedValue` 跨边界传播
  （异步用 `ContextExecutor`），MDC 作显示层（`freeway.log.mdc` 已支持）。
  出站注入/入站提取 **W3C `traceparent`**（`00-traceid-spanid-flags`，
  与 OTel 互操作），经 `TracePropagator` 走 §3.4 统一管线。
- 传播接线：入站 `PropagationFilter`（HttpFilter，类贡献注入
  `List<Propagator>`，extract → merge → `runWith` 绑定请求作用域）；
  出站 `CloudHttpClientDefault` 注入当前 `InvocationContext`（无上下文不
  注入——独立任务由被调方自建根 span）。注意：`Extension<V>` 按设计不可
  注入，消费贡献用 `@Inject List<V>` / `Map<String, V>`。
- commons `Metrics`（primary 绑定覆盖 Noop 内置）+ 贡献 `/metrics` 路由
  （Prometheus 文本格式，手写零依赖；timer 输出
  `name_count` / `name_seconds_total`）。
- 默认 `TracerDefault` / `MetricsDefault`（`observe/`，生产级）。
  `CloudObserveModule` 把模块自有的一个 `MetricsDefault` 注册表按三角色
  绑定：具体类（单例）、`Metrics` SPI（primary，覆盖容器 `NoopMetrics`
  内置）与 `/metrics` 路由读取的 `MetricsSnapshot` 导出视图——三方永远
  指向同一注册表。替换导出后端 = 以子模块装配方式**不装**
  `CloudObserveModule`，自行 primary 绑定 `Metrics`/`Tracer` 并提供导出
  路由（见 CloudObserveModule javadoc）；freeway-ext 暂无 OTel 导出适配器。

### 5.6 韧性（resilience）

- `CircuitBreaker`（滑动窗口 + 半开）、`RateLimiter`（令牌桶）、
  `Retryer`（指数退避）。默认实现用 JDK 并发原语，零依赖。
  `CircuitBreakerDefault`：失败计数滑动窗口（默认 60s），超
  `failure-threshold`（默认 5）转 OPEN；OPEN 持续 `open-window`（默认
  30s）后放行单个半开探测，成功回 CLOSED、失败重开；成功重置失败窗口。
  `RateLimiterDefault`：令牌桶，burst 默认 1（严格速率）。
- **默认优先在 `CloudHttpClient` 层统一生效**（最稳定、最容易落地的
  路径，见 §5.2 编排）。编排顺序：rate-limiter → breaker → 选实例 →
  发送；5xx/连接/超时进重试+熔断，重试重新选实例。**本地拒绝语义**：
  circuit-open / rate-limited 是 retryable=false 的 `CloudException`
  （限流重试会立即再失败），且计入 `cloud.rpc.failures` 指标；
  限流先于熔断——本地拒绝不消耗半开探针名额。派发期的非预期本地异常
  （坏 URL/头、discovery 后端缺陷）统一映射为
  `CloudException.dispatch`（retryable=false），保证调用面单一、半开
  探针总有结局。`@Retry`/`@CircuitBreak`/`@RateLimit` 注解 +
  `Advisor` 织入本地接口服务为后期可选（AOP 仅接口→实现约束）。
- 配置键：`freeway.cloud.rpc.retry.*` / `circuit-breaker.*` /
  `rate-limit.*`（熔断滑动窗口秒数由
  `circuit-breaker.failure-window` 控制，默认 60）；
  `circuit-breaker.enabled=false` → NOOP、
  `rate-limit.enabled=false` → 无限。CloudResilienceModule 未安装时
  client 退化到内置默认（max 3 重试 / 100ms 起退避 / 阈值 5 / 无限限流），
  默认值与配置层同源于 `CloudConfigKeys` 的 `*_DEFAULT` 常量。
  数字键解析失败以 `IllegalArgumentException` 快速失败，消息携带键名与原值。

### 5.7 健康检查（health）

- 由 `CloudHealthModule` 以 Route 贡献实现两个**路径固定（不可配）**的
  端点（K8s 探针语义）：
  - `/health/live` —— 进程存活（固定 `{"status":"ok"}`）；
  - `/health/ready` —— 依赖就绪，`ReadyHandler`（internal/）聚合
    `CloudHealthContributor` 集合（contribute 模式），全健康 200、
    否则 503。注册表就绪检查（`RegistryHealthContributor`）随
    `CloudDiscoveryModule` 交付并贡献；单独安装 health 模块时集合为空
    （恒 ok）。外部后端连通性检查由替换注册/存储后端的适配器自备
    （freeway-ext 未交付）。
- 与 freeway-http 自身的 `/healthz`（`freeway.http.health.*` 可配）是
  两套端点：http 探针在 `HttpModule`/`HealthFilter`，cloud 探针仅在
  安装 `CloudHealthModule` 时注册——两者语义独立，勿配成同一路径互相
  抢占。
- 关停顺序固定：先反注册（摘流量）→ 关 HTTP → 关云连接（§6.2）。

### 5.8 对象存储（storage，可选）

- `ObjectStorage`：get / put / delete / list / presignedUrl——**同步 API**，
  遵循 `Database`/`Pool` 模式，virtual thread 处理并发。
- `ObjectMetadata` / `ObjectEntry` / `PutResult` / `StorageException`。
- 默认 `ObjectStorageDefault`：本地文件系统（`root/bucket/key`）。**路径
  安全**（对照 freeway-http staticfile 回归要求）：bucket 校验（禁
  `..`/分隔符）、key normalize 后禁绝对路径与 `..` 前缀、读写删路径均
  `toRealPath` 校验落在挂载根内；**写入走临时文件 + 原子替换**
  （ATOMIC_MOVE，降级 REPLACE_EXISTING）——目标处即使被植入 symlink 也
  是被替换为链接本身而非被跟随，检查与写入之间无 TOCTOU 窗口；
  `list` 跳过指向根外的 symlink（与 get 的拒绝语义一致，避免泄露外部
  文件名）。`delete` 仅在真实移除（symlink 或普通文件）时发
  `ObjectDeletedEvent`——**不存在的键是 no-op，不发幽灵删除事件**。
  `presignedUrl` 本地无签名语义返回 empty；etag 为 SHA-256、
  versionId 每次写入新 UUID（`list` 条目的 etag 是 size+mtime 派生值，
  非内容哈希）。`storage.base-path` 默认工作目录下 `cloud-storage`。
- 领域事件 `ObjectStoredEvent` / `ObjectDeletedEvent`（EventBus，经构造
  注入的发布回调）。
- 与主链路（discovery/rpc/observe/resilience）解耦，可独立安装。

### 5.9 服务间安全（跨能力，无独立大模块）

- **传输加密（mTLS）**：`TransportSecurity` 抽象（`rpc/` 包）。core 用
  JDK `SSLContext` + 文件型证书加载（`freeway.cloud.rpc.tls.*` 键，
  PKCS12/JKS keystore/truststore）：`CloudRpcModule` 按键解析——keystore
  为空 → 接口常量 `TransportSecurity.NONE`（开发态明文），否则构建
  `TransportSecurityImpl`（internal/，`fromKeyStore`）。能力由运行时配置
  决定，静态 marker 表达不了条件能力，故 core 不预铺 `@None`/`@Mtls`
  注解；自定义传输安全实现（Vault 动态证书等）直接 bind
  `TransportSecurity` 即可。出站 `CloudHttpClient` 应用它。
- **身份传播**：`PrincipalContext` 经 `InvocationContext` +
  `AuthPropagator`（internal/）注入/提取 `x-principal` /
  `x-principal-roles` 头（与 traceparent 同序，同一管线）。传播**已验证
  身份**，不传播原始凭据。**信任边界**：入站提取信任传播头——**默认
  关闭**（`freeway.cloud.auth.extract.enabled=false`），仅在可信服务网内
  显式开启；出站注入恒开（只转发本地已验证身份）。生产 token 校验
  （JWT/Opaque）是自定义安全模块的职责：core 不内置，freeway-ext 亦未
  交付——真实验证逻辑须由部署者提供（装在外层过滤器/网关上）。
- **密钥源**：§5.4 `SecretStore`；`SecretSymbolSource`（`secret/`）参与
  `SymbolSource` 链，以 `order()=15` 高于所有文件源（env 层 10 与文件层
  20 之间）——与伞模块安装顺序无关。
- 范围边界：服务间（service-to-service）云原生安全，**不是**应用级登录/
  会话框架。
- 服务端鉴权回归应用自身 Route/Filter 职责（无 `/rpc/*` 需要守卫）。

## 6. Marker / Primary / RuntimeHook 用法

### 6.1 标记定义（`annotation/`，全部 `@Retention(RUNTIME)` 空注解）

| 类别 | 注解 |
|---|---|
| 后端 | `@Local`（本地默认实现） |

core 只保留**实际被装配引用**的标记：`@Local`（本地默认实现）。其余
后端注解（`@Nacos` / `@Consul` / `@Kubernetes` / `@Grpc` 等）不预铺——
遵循 "Prefer small explicit APIs over future-proof abstractions"；若未来
需要，随交付该后端的适配器/应用模块自行定义。freeway-ext 目前不含任何
cloud 适配器（现有模块仅 `freeway-db-hikari` / `freeway-http-undertow` /
`freeway-http-jetty` / `freeway-mq-kafka` / benchmark），不随附 cloud
注解。负载均衡策略无需注解：`LoadBalancer` 是 `@FunctionalInterface`，
自定义策略直接 bind 一个 primary 实现即可。

规则（基于 ioc `MarkerIndex` 的 `containsAll` 语义）：

- 本地默认实现统一 `@Local` 标记，保证"不指定 marker 必有默认"。
- 装配面若使用未注册的后端标记（对应适配器未装、注解不在
  `MarkerIndex` 内），ioc 忽略该注解并以默认绑定解析——每个
  `(annotation, owner)` 组合只告警一次（`InjectionResolver` 一次性警告，
  非完全静默）。后端类型配置键（`freeway.cloud.secret/discovery/registry/
  storage.type`）配置了外部后端而本地实现仍生效时，`BackendTypeGuard`
  启动时告警一次，不静默丢弃（§8）。文档明示该行为，避免误用。
- marker 是静态注解类型（无值），分类实现/模块；运行时实例属性
  （zone/weight/canary 路由）走 `ServiceInstance.metadata` +
  `LoadBalancer`，不在 marker 上做文章。
- 接线期 canary（整模块切变体）是 marker 的活；运行时 canary 路由
  （按实例 metadata 切流量）是 `LoadBalancer` 的活，两者不混淆。

### 6.2 生命周期（RuntimeHook 排序）

```
start:
  freeway.cloud.secret / discovery / storage / events ── before("freeway.http.server")
  freeway.http.server                          （WebServer 启动，host:port 已知）
  freeway.cloud.registry  ── after("freeway.http.server")
                       （收集 ServiceDeclaration，统一 register）

stop（逆序）:
  freeway.cloud.registry  ── deregister 自注册（先摘除流量）
  freeway.http.server     ── stop server
  freeway.cloud.*         ── close connections
```
（hook 名以 `CloudHooks.java` 为准；`freeway.cloud.config` hook 已随
配置中心删除，见 §5.3。events 网格的 `freeway.cloud.events` hook 仅在
显式安装 `CloudEventModule` 时注册。）

- `freeway.cloud.registry` 必须在 `freeway.http.server` **之后**启动
  （地址只有服务器启动后可知）、**之前**停止。
- **依赖约束**：注册 hook 的排序引用要求 `freeway.http.server` 存在。
  `freeway-http` 通过 `META-INF/services` SPI 自动发现（默认开启），实际
  部署几乎总是可用；关闭 `autoDiscovery` 且不装 HttpModule 时启动失败
  （`HookLifecycle` 严格校验排序引用，fail fast），这是显式设计。
- 内建 `HttpServiceDeclaration` 注册 `WebServer` 地址，serviceId 默认
  `freeway.cloud.registry.service-id` → `freeway.app.name`；host 由
  `registry.service-host` 覆盖（0.0.0.0 / K8s POD_IP 注入）；instanceId
  默认派生键（`service-id@host:port`），可经
  `registry.service-instance-id` 钉住。注册 bind-all 地址
  （`0.0.0.0` / `::`）时启动告警，提示配置其他节点可达的 service-host。
- 优雅关停复用既有能力：`freeway.http.server.shutdown-grace` +
  `AppStoppingEvent`（close 时先发布再逆序停 hooks）+ JVM shutdown hook，
  不重造。
- `CloudHttpClient` 持有 JDK `HttpClient`（持久连接池 + selector 线程），通过
  `@PreDestroy` 在容器关闭时恰好释放一次。

## 7. 配置键（CloudConfigKeys，吸收 design-A 清单）

<!-- 实际键清单与源码同步：见 freeway-cloud/src/main/java/.../CloudConfigKeys.java -->

```java
public final class CloudConfigKeys {
    private CloudConfigKeys() {}
    static final String PREFIX = "freeway.cloud";

    // ── Secret ─────────────────────────────────────────────
    public static final String SECRET_TYPE = PREFIX + ".secret.type";
    public static final String SECRET_FILE = PREFIX + ".secret.file";
    public static final String SECRET_KEYS = PREFIX + ".secret.keys";   // 符号名白名单（仅 -D）

    // ── Object Storage ─────────────────────────────────────
    public static final String STORAGE_TYPE       = PREFIX + ".storage.type";
    public static final String STORAGE_BASE_PATH  = PREFIX + ".storage.base-path";
    public static final String STORAGE_BASE_PATH_DEFAULT = "cloud-storage";
    // bucket/region/endpoint 等 S3 族键随外部存储适配器交付，core 不预铺（无交付，见 §8）

    // ── Discovery / Registry ───────────────────────────────
    public static final String DISCOVERY_TYPE   = PREFIX + ".discovery.type";
    public static final String REGISTRY_TYPE    = PREFIX + ".registry.type";
    public static final String REGISTRY_SERVICE_ID = PREFIX + ".registry.service-id";
    public static final String REGISTRY_SERVICE_HOST = PREFIX + ".registry.service-host";
    public static final String REGISTRY_SERVICE_SCHEME = PREFIX + ".registry.service-scheme";
    public static final String REGISTRY_SERVICE_PORT = PREFIX + ".registry.service-port";
    public static final String REGISTRY_SERVICE_INSTANCE_ID = PREFIX + ".registry.service-instance-id";
    public static final String REGISTRY_SERVICE_SCHEME_DEFAULT = "http";

    // ── RPC ────────────────────────────────────────────────
    public static final String RPC_CONNECT_TIMEOUT     = PREFIX + ".rpc.connect-timeout";
    public static final String RPC_REQUEST_TIMEOUT     = PREFIX + ".rpc.request-timeout";
    public static final String RPC_RETRY_MAX_ATTEMPTS  = PREFIX + ".rpc.retry.max-attempts";
    public static final String RPC_RETRY_BACKOFF_BASE  = PREFIX + ".rpc.retry.backoff-base";
    public static final String RPC_RETRY_BACKOFF_MAX   = PREFIX + ".rpc.retry.backoff-max";
    public static final String RPC_CB_ENABLED          = PREFIX + ".rpc.circuit-breaker.enabled";
    public static final String RPC_CB_FAILURE_THRESHOLD = PREFIX + ".rpc.circuit-breaker.failure-threshold";
    public static final String RPC_CB_FAILURE_WINDOW   = PREFIX + ".rpc.circuit-breaker.failure-window";
    public static final String RPC_CB_OPEN_WINDOW      = PREFIX + ".rpc.circuit-breaker.open-window";
    public static final String RPC_RATE_LIMIT_ENABLED  = PREFIX + ".rpc.rate-limit.enabled";
    public static final String RPC_RATE_LIMIT_PER_SECOND = PREFIX + ".rpc.rate-limit.per-second";
    public static final String RPC_TRACE_ENABLED       = PREFIX + ".rpc.trace.enabled"; // 模块默认开

    // Canonical defaults（类型化常量，紧邻键）——CloudResilienceModule 配置
    // fallback 与 CloudHttpClientDefault 库级 fallback 共享同一来源
    public static final long   RPC_REQUEST_TIMEOUT_DEFAULT     = 10_000;
    public static final long   RPC_CONNECT_TIMEOUT_DEFAULT     = 3_000;
    public static final int    RPC_RETRY_MAX_ATTEMPTS_DEFAULT  = 3;
    public static final long   RPC_RETRY_BACKOFF_BASE_DEFAULT  = 100;
    public static final long   RPC_RETRY_BACKOFF_MAX_DEFAULT   = 5000;
    public static final int    RPC_CB_FAILURE_THRESHOLD_DEFAULT = 5;
    public static final long   RPC_CB_FAILURE_WINDOW_DEFAULT   = 60;
    public static final long   RPC_CB_OPEN_WINDOW_DEFAULT      = 30;
    public static final double RPC_RATE_LIMIT_PER_SECOND_DEFAULT = 100;

    // ── RPC / TLS（空串默认 = 明文开发态）────────────────────
    public static final String RPC_TLS_KEY_STORE          = PREFIX + ".rpc.tls.key-store";
    public static final String RPC_TLS_KEY_STORE_PASSWORD = PREFIX + ".rpc.tls.key-store-password";
    public static final String RPC_TLS_TRUST_STORE        = PREFIX + ".rpc.tls.trust-store";
    public static final String RPC_TLS_TRUST_STORE_PASSWORD = PREFIX + ".rpc.tls.trust-store-password";
    public static final String RPC_TLS_KEY_STORE_DEFAULT = "";
    public static final String RPC_TLS_KEY_STORE_PASSWORD_DEFAULT = "";
    public static final String RPC_TLS_TRUST_STORE_DEFAULT = "";
    public static final String RPC_TLS_TRUST_STORE_PASSWORD_DEFAULT = "";

    // ── Auth propagation ────────────────────────────────────
    public static final String AUTH_EXTRACT_ENABLED = PREFIX + ".auth.extract.enabled"; // 默认关

    // ── CloudEventBus（WS 事件网格，见 freeway-cloud-events-design.md）──
    public static final String EVENTS_ENABLED        = PREFIX + ".events.enabled";
    public static final String EVENTS_PEERS          = PREFIX + ".events.peers";
    public static final String EVENTS_SUBSCRIPTIONS  = PREFIX + ".events.subscriptions";
    public static final String EVENTS_ALLOWED_TYPES  = PREFIX + ".events.allowed-types";
    public static final String EVENTS_ALLOWED_TOPICS = PREFIX + ".events.allowed-topics";
    public static final String EVENTS_TOKEN          = PREFIX + ".events.token";
    public static final String EVENTS_DEDUP_ENABLED  = PREFIX + ".events.dedup.enabled";
    public static final String EVENTS_DEDUP_CAPACITY = PREFIX + ".events.dedup.capacity";
    public static final int    EVENTS_DEDUP_CAPACITY_DEFAULT = 4096;
    public static final String EVENTS_PATH_DEFAULT   = "/cloud/events";
    public static final String EVENTS_CONNECT_TIMEOUT_MS   = PREFIX + ".events.connect-timeout-ms";
    public static final long   EVENTS_CONNECT_TIMEOUT_MS_DEFAULT   = 3000;
    public static final String EVENTS_HANDSHAKE_TIMEOUT_MS = PREFIX + ".events.handshake-timeout-ms";
    public static final long   EVENTS_HANDSHAKE_TIMEOUT_MS_DEFAULT = 10_000;
    public static final String EVENTS_BACKOFF_BASE_MS = PREFIX + ".events.backoff-base-ms";
    public static final long   EVENTS_BACKOFF_BASE_MS_DEFAULT = 1000;
    public static final String EVENTS_BACKOFF_MAX_MS  = PREFIX + ".events.backoff-max-ms";
    public static final long   EVENTS_BACKOFF_MAX_MS_DEFAULT  = 30_000;
}
```
（`freeway.cloud.config.*` 旧键已随配置中心删除，见 §3.5/§5.3。）

## 8. core 与 freeway-ext 的边界

**core（`freeway-cloud`，零依赖，本方案全部 Phase）**：接口 + 可替换
默认实现（`XDefault`，位于各自功能包；`*Impl` 保留给无替换面的实现）+
`@Local` 标记。ext 不是 core 的"补完"，而是 freeway **选择不自建**的
能力（需第三方服务/客户端）的**可选替代**——接入协议与现状如下：

- core 交付的默认实现（均可被替代绑定覆盖）：
  `ServiceDiscoveryDefault` / `ServiceRegistryDefault` /
  `LoadBalancerDefault`（`discovery/`）、`CloudHttpClientDefault`
  （`rpc/`）、`TracerDefault` / `MetricsDefault`（`observe/`）、
  `CircuitBreakerDefault` / `RateLimiterDefault` / `RetryerDefault`
  （`resilience/`）、`SecretStoreDefault`（`secret/`）、
  `ObjectStorageDefault`（`storage/`，本地文件系统）、
  `TransportSecurity.NONE` 常量与 `TransportSecurityImpl`（`internal/`，
  按 `freeway.cloud.rpc.tls.*` 键择一，无独立标记）。
- **接入协议**（自定义后端与未来 ext 适配器一致）：绑定对应接口的
  替代实现并 `.primary()`；如自带后端注解则随适配器定义并注册为
  marker（core 不预铺，§6.1）。后端选择键 `freeway.cloud.secret.type` /
  `discovery.type` / `registry.type` / `storage.type` 在替代实现生效时被
  其消费；仍用本地实现时 `BackendTypeGuard` 对非空且非 `local` 的值
  启动告警一次（§6.1）。类型键默认空 = 本地后端。
- **freeway-ext 现状**：仓库模块为 `freeway-db-hikari` /
  `freeway-http-undertow` / `freeway-http-jetty` / `freeway-mq-kafka` /
  benchmark——**不含任何 cloud 适配器**（无 Nacos/Consul/K8s 注册发现、
  S3、Vault/KMS、OTel 导出、gRPC/WebClient 传输、JWT/Opaque 校验的
  交付实现），也不随附 cloud 注解。本文不再以"ext 提供 X"声称未交付
  能力；需要外部后端时按上述协议自建或等待 ext 交付。

## 9. 实施阶段（对齐 implementation-plan Phase 0–8）

| Phase | 内容 |
|---|---|
| 0 | 脚手架：Maven 模块 + 根 pom 追加 + 包结构 + CloudModule + 子模块 + 注解 |
| 1 | 核心对象 + 本地默认（ServiceId/Endpoint/ServiceInstance/InvocationContext/CloudConfig + 全部 XDefault + `.primary()`）（实施期措辞——后修正：ServiceId 无公开类型、默认以 `@Local` 装配，§3.1/§6.1；CloudConfig 已删，§3.5） |
| 2 | 注册发现 + 远程调用（Registry/Discovery/Declaration/LoadBalancer + CloudHttpClient + 心跳 + trace 头注入 + 重试换实例） |
| 3 | 配置中心（CloudConfig 热刷新 + ConfigRef + ConfigChangedEvent + 动态 SymbolProvider）——该配置中心已随 1.4.0 删除（§3.5/§5.3） |
| 4 | 可观测性（Tracer + ScopedValue/MDC + W3C 传播 + /metrics + /health/live|ready） |
| 5 | 韧性（熔断/限流/重试 + CloudHttpClient 集成 + Advisor 注解可选） |
| 6 | 安全（PrincipalContext + 传播 + mTLS 抽象 + SecretStore SymbolSource 接线） |
| 7 | 对象存储（可选，不影响核心链路） |
| 8 | freeway-ext 云后端适配器（另案——未交付、无排期，见 §12.1） |

## 10. 明确不做

- 透明远程 bean / `@CloudClient` 接口代理 / `CloudExporter` 服务端导出 /
  `/rpc/*` **私有**协议（二进制/多路复用面）。注意区分：基于
  `CloudHttpClient` 的 **JSON 显式 topic RPC**（CallBus 远端桥接，
  文档见 `freeway-remote-callbus-design.md`）不在排除之列——它以
  HTTP 为传输、以声明式 mapping 为边界，无注解魔法、无自动导出。
- classpath 扫描式自动注册（`ServiceLoader` 除外，可关）。
- 业务数据进入 `InvocationContext`；实例属性进入 `@Marker`。
- 第三方 SDK 进入 core。
- 分布式事务、分布式锁（协调型，超范围）；MQ/事件桥接（`EventSink`
  接缝已有，适配器在 ext）；API 网关/服务网格/Serverless/分布式调度。

## 11. 事实修正记录（相对早期文档，均已对照现有代码核实）

1. **`install()` 去重**：按模块**实例身份**（`IdentityHashMap`），非
   class。伞模块与子模块不可混装（§4.1）。
2. **`Coercer` 无三参重载**：`Coercer` 接口只有
   `coerce(Object, Class<T>)`。metadata accessor 用两参 + 默认值短路
   （null 检查），不新增 core API。
3. **`AppConfig` 是接口**（`AppConfigDefault` record 实现），承诺不可变
   快照语义——表述修正为"接口 + record 实现"。
4. **marker 静默回退不彻底静默**：未注册 marker 注解有一次 warning 日志
   （`InjectionResolver` 一次性警告）。规避约定（本地默认 `@Local`
   标记兜底，§6.1）仍然成立。
5. **命名位置**：默认实现是功能包内的公开扩展点（`XDefault`），不在
   `internal/`（CLAUDE.md 命名规则）——早期"默认实现在 `internal/`"
   的表述已修正（§1/§4/§8）。
6. **测试框架**：根 pom `junit.version=6.1.3`（JUnit 6.x，非 5.12）。
7. **`ServiceId` 不造公开类型**：遵循 CLAUDE.md（"ServiceId is
   intentionally not a public type"）——serviceId 是普通字符串，守卫由
   ioc `ServiceIds.normalize`（绑定 id 隐式）承担，cloud 不引入独立
   `ServiceId` record（§3.1）。

## 12. 实施状态与后续工作

**core 已完成（2026-08-19 定稿；当前 115 个测试全绿）**：Phase 0–7 全部
落地——脚手架、核心对象与本地默认、注册发现与远程调用、可观测性、
韧性、安全、对象存储（Phase 3 配置中心落地后已随 1.4.0 删除，
§3.5/§5.3）。实现细节与偏差见各 § 与 §11。

### 12.1 Phase 8（freeway-ext 云后端适配器——未交付、无排期）

云后端适配器属 freeway-ext（独立仓库）：每个用 `.primary()` 覆盖 core
默认，必要时自带后端 marker 与类型键消费（`BackendTypeGuard` 提示）。
**必须为完整实现（非占位）**，遵循 freeway-ext 既有惯例（参照
`freeway-db-hikari`）；适配器是"可选替代"而非"补完"。

接入协议与 freeway-ext 现状见 §8：**目前 freeway-ext 不含任何 cloud
适配器，具体后端与模块名单未排期**——本文不再罗列臆测的模块表。
若开工：pom + 实现类 + 模块绑定 + 真实后端测试；测试后端策略
（testcontainers vs 本地单机）届时定。

### 12.2 core 可选后续

- `@Retry` / `@CircuitBreak` / `@RateLimit` 注解 + `Advisor` 织入本地接口
  服务（§5.6 后期可选）。
- 动态配置用法示例文档：`@Symbol` / `@Value` 与符号链见
  `docs/freeway-config.md`（cloud 密钥 provider 的 `secret.keys` 白名单
  用法见 §5.4）。
