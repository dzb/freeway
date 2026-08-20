# freeway-cloud 完整设计（定稿）

> **状态：定稿（2026-08-19）**。本文档是 freeway-cloud 的唯一设计基线，
> 取代早期并行的 design-A（路径级 RpcClient 线）与 design-B（方法级 RPC
> 线）两套方案（相关早期文档已移除）。
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
- 接口在 core、默认实现隔离在 `internal/`、云后端适配器在 freeway-ext、
  IoC 接线集中在 `*Module`。
- 无类路径扫描、无字节码织入、无透明远程 bean。
- `.primary()` 指定默认实现，`@Marker` 选择变体/后端（静态，无值）。
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

freeway-http 的用途：`/health/live|ready` 端点、`PropagationFilter`
（`HttpFilter` 扩展点）、`/metrics` 路由、trace 头注入。依赖方向单向
（cloud→http），http 零外部依赖，无传递负担。只装配置等子模块的用户
在 classpath 上多一个 core jar，无运行时开销。

## 3. 核心对象（5 个）

| 对象 | 职责 |
|---|---|
| `ServiceId`（普通字符串） | 是谁 |
| `ServiceInstance` | 有哪些可用实例 |
| `Endpoint` | 怎么到达 |
| `InvocationContext` | 跨边界传播什么 |
| `CloudConfig` | 运行时配置从哪里来 |

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
public record Endpoint(String scheme, String host, String port, String basePath) {
    public URI uri() { ... }
}
```

覆盖 scheme/port/basePath、DNS 名、K8s Service FQDN、mesh sidecar、非 IP
定位。不做裸字符串拼接。

### 3.3 ServiceInstance —— 实例模型

```java
public record ServiceInstance(
    ServiceId          serviceId,    // 逻辑身份
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
- 传播统一由 `Propagator` 处理：`extract(headers, ic)` / `inject(ic, headers)`。
  内置 `TracePropagator`（W3C `traceparent`：`00-traceid-spanid-flags`）、
  `AuthPropagator`（`Authorization: Bearer <token>`）、`BaggagePropagator`。
  新增关注点 = 贡献一个 `Propagator`，不改 core。
- 两个边界应用点：`PropagationFilter`（`HttpFilter`，入站
  `extract` → `enter(ic)`）与 `CloudHttpClientDefault`（出站注入当前 ic）。
- 进程内传播：`ScopedValue` 承载；异步跨线程用 `ContextExecutor`
  （freeway-commons）显式传播。**MDC 只作显示层**（`JULMDCAdapter` 是
  ThreadLocal 型、不跨线程、虚拟线程终止即清理），上下文载体必须是
  `ScopedValue`。

### 3.5 CloudConfig —— 运行期动态配置源

与 `AppConfig`（启动期不可变快照——接口 + record 实现，`asMap()` 返回
不可变快照）的关系：`AppConfig` 是启动快照，`CloudConfig` 是运行期动态源，
动态配置不反向污染启动快照。详见 §5.3。

## 4. 包结构与装配

```
com.jujin.freeway.cloud
├── CloudConfigKeys.java       集中配置键（§7）
├── CloudModule.java           伞模块（聚合安装）
├── annotation/                后端/策略/能力标记（§6.1）
├── context/                   InvocationContext, TraceContext, PrincipalContext,
│                              Baggage, Propagator (+ 内置 Propagator)
├── config/                    CloudConfig, ConfigRef, ConfigChangedEvent
├── secret/                    SecretStore, SecretSymbolSource（§5.4）
├── discovery/                 ServiceInstance, Endpoint, Health,
│                              ServiceDiscovery, ServiceRegistry,
│                              ServiceDeclaration, LoadBalancer
├── rpc/                       CloudHttpClient, CloudRequest, CloudResponse,
│                              CloudException（§5.2）
├── observe/                   Tracer, MeterRegistry（§5.5）
├── resilience/                CircuitBreaker, RateLimiter, Retryer（§5.6）
├── health/                    CloudHealthContributor, HealthResult（§5.7）
├── storage/                   ObjectStorage（可选，§5.8）
└── internal/                  全部 XDefault 实现
```

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
        b.install(new CloudConfigModule());
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

## 5. 能力设计

### 5.1 注册发现（discovery）

接口职责固定：

- `ServiceRegistry`：register / renew / deregister（生命周期）
- `ServiceDiscovery`：getInstances / subscribe（查询）
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

- `LoadBalancer`：策略接口，只负责 RPC 出站调用前的实例选择，不负责
  集群入口流量调度（K8s Service/Ingress/网关属基础设施）。内置
  round-robin（默认）/ random / weighted / zone-aware，`@Marker` 选变体，
  运行时参数读 `ServiceInstance.metadata`。
- 默认实现（`internal/`）：`ServiceDiscoveryDefault` /
  `ServiceRegistryDefault` —— 进程内注册表 + 定时清理（lastSeen 驱逐），
  生产可用（单进程/静态拓扑场景）；跨进程动态发现由 ext 后端提供。
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
}

public record CloudRequest(String method, String path, Map<String,String> headers, byte[] body) {
    static CloudRequest get(String path);
    static CloudRequest post(String path, byte[] body, String contentType);
}

public record CloudResponse(int status, Map<String,List<String>> headers, byte[] body) {
    <T> T bodyAs(Class<T> type, JsonCodec codec);
    boolean is2xx();
}
```

**调用链**（`CloudHttpClientDefault`，`internal/`，包 JDK `HttpClient`）：

```
discovery.getInstances(serviceId)
  → loadBalancer.choose(instances, ctx)      // 只选 live && ready
  → endpoint + path 拼 URL
  → 注入 InvocationContext（traceparent / principal / baggage 头）
  → 韧性编排（retry → circuit-breaker → rate-limit → 超时）
  → httpClient.send(...)                     // 虚拟线程同步阻塞
  → CloudResponse
```

- **被调方零要求**：就是普通 Freeway HTTP 应用，Route 照常贡献。
  无 `/rpc/*` 私有协议、无方法级派发、无跨边界异常序列化。
- 重试**必须重新选实例**（换 discovery 刷新后的不同实例），不 hammer
  死实例。连接失败/超时 retryable；**5xx 抛 `CloudException`（status>=500，
  retryable）进重试+熔断统计；4xx 作为响应返回**（调用方拥有 body，
  不重试）。`CloudException` 携带 retryable 标志。
- 超时：每调用 `HttpRequest.timeout(Duration)`，键
  `freeway.cloud.rpc.connect-timeout` / `request-timeout`。
- 默认：`bind(CloudHttpClient).to(CloudHttpClientDefault).primary()`；
  ext 可换 WebClient/gRPC 传输（`@Http`/`@Grpc` 标记）。
- 响应侧 `bodyAs(Class, JsonCodec)` 与服务端用同一 `JsonCodec`
  （record/泛型/java.time 支持已验证）。
- **明确不做**：`@CloudClient` 接口代理、`CloudExporter` 服务端导出、
  透明远程 bean、方法名→路径映射。类型安全收益由应用层小封装获得
  （`bodyAs(Class)` 泛型返回已支持），不引入框架级代理与派发协议。

### 5.3 配置中心（config）

- `CloudConfig`：`get(key)` / `asMap()`（受控快照）/ `watch(key, listener)`
  / `addListener(ConfigListener)` / `reload()`。
- `ConfigRef<T>`：包装显式读最新值（主动拉取，无"字段自动变"魔法）。
- 变更发布 `ConfigChangedEvent` 到 `EventBus`（响应式重绑的钩子）。
- 贡献为**动态 `SymbolProvider`**：`@Value`/`@Symbol` 解析时读取最新值
  （contributed 优先级高于 system/env 默认链，`SymbolSourceDefault`
  已支持），不破坏 `AppConfig` 启动快照。
- 默认 `CloudConfigDefault`：`WatchService` 监听文件变化热重载，生产可用。
  变更经 `ConfigChangedEvent` 发 EventBus；**首次加载不发变更事件**。
  文件路径来自 `freeway.cloud.config.file`（系统属性）或默认
  `application-cloud.properties`——刻意不走 `SymbolSource` 解析（配置
  provider 自身参与符号解析，路径再走会递归），provider 以类贡献注册
  （ioc on-demand 惰性化）。`freeway.cloud.config` hook 在关停时停止
  watch 线程。

### 5.4 密钥（secret）—— 独立于 config

| 维度 | CloudConfig | SecretStore |
|---|---|---|
| `asMap()` | 允许 | **禁止**（密钥不可批量暴露） |
| 缓存策略 | 长缓存 + 监听 | TTL 控制，支持轮换 |
| 默认值 | 允许 fallback 到本地 | **禁止**（密钥必须显式配置） |
| 适配器来源 | K8s ConfigMap / AWS SSM | K8s Secret / AWS Secrets Manager / Vault |

```java
public interface SecretStore {
    Optional<String> get(String key);
    default Optional<byte[]> getBytes(String key) { ... }
}
```

- `SecretSymbolSource` 包装 `SecretStore` 参与 `SymbolSource` 解析，
  使 `@Symbol("db.password")` 可解析密钥。
- 默认 `SecretStoreDefault`：env/file；ext：Vault/KMS。
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
- `MeterRegistry`（counter/timer/gauge，内存默认）+ 贡献 `/metrics` 路由
  （Prometheus 文本格式，手写零依赖；timer 输出
  `name_count` / `name_seconds_total`）。
- 默认 `TracerDefault` / `MeterRegistryDefault`（`internal/`，生产级）；
  ext：OTel 导出器（OTLP/Prometheus remote-write）。

### 5.6 韧性（resilience）

- `CircuitBreaker`（滑动窗口 + 半开）、`RateLimiter`（令牌桶）、
  `Retryer`（指数退避）。默认实现用 JDK 并发原语，零依赖。
  `CircuitBreakerDefault`：失败计数滑动窗口（默认 60s），超
  `failure-threshold`（默认 5）转 OPEN；OPEN 持续 `open-window`（默认
  30s）后放行单个半开探测，成功回 CLOSED、失败重开；成功重置失败窗口。
  `RateLimiterDefault`：令牌桶，burst 默认 1（严格速率）。
- **默认优先在 `CloudHttpClient` 层统一生效**（最稳定、最容易落地的
  路径，见 §5.2 编排）。编排顺序：breaker → rate-limiter → 选实例 →
  发送；5xx/连接/超时进重试+熔断，重试重新选实例。**本地拒绝语义**：
  circuit-open / rate-limited 是 retryable=false 的 `CloudException`
  （限流重试会立即再失败）。`@Retry`/`@CircuitBreak`/`@RateLimit` 注解 +
  `Advisor` 织入本地接口服务为后期可选（AOP 仅接口→实现约束）。
- 配置键：`freeway.cloud.rpc.retry.*` / `circuit-breaker.*` /
  `rate-limit.*`；`circuit-breaker.enabled=false` → NOOP、
  `rate-limit.enabled=false` → 无限。CloudResilienceModule 未安装时
  client 退化到内置默认（max 3 重试 / 100ms 起退避 / 阈值 5 / 无限限流）。

### 5.7 健康检查（health）

- 拆分两个端点（K8s 探针语义），以 Route 贡献实现：
  - `/health/live` —— 进程存活（固定 `{"status":"ok"}`）；
  - `/health/ready` —— 依赖就绪，`ReadyHandler` 聚合
    `CloudHealthContributor` 集合（contribute 模式，各适配器贡献自己的
    检查，如 s3/configmap/discovery），全健康 200、否则 503。
- 建立在 `freeway.http.health.*` 配置键之上（路径可配）。
- 关停顺序固定：先反注册（摘流量）→ 关 HTTP → 关云连接（§6.2）。

### 5.8 对象存储（storage，可选）

- `ObjectStorage`：get / put / delete / list / presignedUrl——**同步 API**，
  遵循 `Database`/`Pool` 模式，virtual thread 处理并发。
- `ObjectMetadata` / `ObjectEntry` / `PutResult` / `StorageException`。
- 默认 `ObjectStorageDefault`：本地文件系统（`root/bucket/key`）。**路径
  安全**（对照 freeway-http staticfile 回归要求）：bucket 校验（禁
  `..`/分隔符）、key normalize 后禁绝对路径与 `..` 前缀、读路径
  `toRealPath` 必须落在挂载根内、写前删除目标处的已存在 symlink（防
  投毒）。`presignedUrl` 本地无签名语义返回 empty；etag 为 SHA-256、
  versionId 每次写入新 UUID。`storage.base-path` 默认工作目录下
  `cloud-storage`。
- 领域事件 `ObjectStoredEvent` / `ObjectDeletedEvent`（EventBus，经构造
  注入的发布回调）。
- 与主链路（discovery/rpc/config/observe/resilience）解耦，可独立安装。

### 5.9 服务间安全（跨能力，无独立大模块）

- **传输加密（mTLS）**：`TransportSecurity` 抽象（`rpc/` 包），core 用 JDK
  `SSLContext` + 文件型证书加载（`freeway.cloud.rpc.tls.*` 键，PKCS12
  keystore/truststore）；Vault 动态证书属 ext。默认 `NONE`（开发态明文，
  `@None`），配置 keystore 后构建 mTLS 上下文（`@Mtls`）。出站
  `CloudHttpClient` 应用它。
- **身份传播**：`PrincipalContext` 经 `InvocationContext` +
  `AuthPropagator` 注入/提取 `x-principal` / `x-principal-roles` 头（与
  traceparent 同序，同一管线）。传播**已验证身份**，不传播原始凭据。
  **信任边界**：入站提取信任传播头（内网拓扑可信）；生产 token 校验
  （JWT/Opaque）是 ext 安全模块（`@Jwt`/`@Opaque`），core 默认为
  开发级明文传播。
- **密钥源**：§5.4 `SecretStore`；`SecretSymbolSource`（`secret/`）参与
  `SymbolSource` 链，**优先级高于配置 provider**（伞模块安装顺序保证）。
- 范围边界：服务间（service-to-service）云原生安全，**不是**应用级登录/
  会话框架。
- 服务端鉴权回归应用自身 Route/Filter 职责（无 `/rpc/*` 需要守卫）。

## 6. Marker / Primary / RuntimeHook 用法

### 6.1 标记定义（`annotation/`，全部 `@Retention(RUNTIME)` 空注解）

| 类别 | 注解 |
|---|---|
| 后端 | `@Local`（默认）/ `@Nacos` / `@Consul` / `@Kubernetes` |
| 传输 | `@Http`（默认）/ `@Grpc` |
| 传输安全 | `@None`（默认，明文）/ `@Mtls` |
| 策略 | `@RoundRobin`（默认）/ `@Random` / `@Weighted` |
| 模块级 | `@Cloud`（能力标签）/ `@Canary`（整模块变体） |

规则（基于 ioc `MarkerIndex` 的 `containsAll` 语义）：

- 本地默认实现统一 `@Local` + `.primary()`，保证"不指定 marker 必有默认"。
- 用了 `@Nacos` 等后端标记必须引入对应 ext 适配器，否则回退默认——实际
  有一次 warning 日志（`InjectResolver` 一次性警告，非完全静默），文档
  明示该行为，避免误用。
- marker 是静态注解类型（无值），分类实现/模块；运行时实例属性
  （zone/weight/canary 路由）走 `ServiceInstance.metadata` +
  `LoadBalancer`，不在 marker 上做文章。
- 接线期 canary（整模块切变体）是 marker 的活；运行时 canary 路由
  （按实例 metadata 切流量）是 `LoadBalancer` 的活，两者不混淆。

### 6.2 生命周期（RuntimeHook 排序）

```
start:
  freeway.cloud.config / secret / discovery  ── before("freeway.http.server")
  freeway.http.server                          （WebServer 启动，host:port 已知）
  freeway.cloud.registry  ── after("freeway.http.server")
                       （收集 ServiceDeclaration，统一 register）

stop（逆序）:
  freeway.cloud.registry  ── deregister 自注册（先摘除流量）
  freeway.http.server     ── stop server
  freeway.cloud.*         ── close connections
```

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
  `registry.service-instance-id` 钉住。
- 优雅关停复用既有能力：`freeway.http.server.shutdown-grace` +
  `AppStoppingEvent`（close 时先发布再逆序停 hooks）+ JVM shutdown hook，
  不重造。
- `CloudHttpClient` 持有 JDK `HttpClient`（持久连接池 + selector 线程），通过
  `@PreDestroy` 在容器关闭时恰好释放一次。

## 7. 配置键（CloudConfigKeys，吸收 design-A 清单）

```java
public final class CloudConfigKeys {
    private CloudConfigKeys() {}
    static final String PREFIX = "freeway.cloud";

    // ── Config ─────────────────────────────────────────────
    public static final String CONFIG_TYPE        = PREFIX + ".config.type";
    public static final String CONFIG_FILE        = PREFIX + ".config.file";

    // ── Secret ─────────────────────────────────────────────
    public static final String SECRET_TYPE        = PREFIX + ".secret.type";
    public static final String SECRET_FILE        = PREFIX + ".secret.file";

    // ── Object Storage ─────────────────────────────────────
    public static final String STORAGE_TYPE       = PREFIX + ".storage.type";
    public static final String STORAGE_BUCKET     = PREFIX + ".storage.bucket";
    public static final String STORAGE_BASE_PATH  = PREFIX + ".storage.base-path";
    public static final String STORAGE_REGION     = PREFIX + ".storage.region";
    public static final String STORAGE_ENDPOINT   = PREFIX + ".storage.endpoint";

    // ── Discovery / Registry ───────────────────────────────
    public static final String DISCOVERY_TYPE        = PREFIX + ".discovery.type";
    public static final String REGISTRY_TYPE         = PREFIX + ".registry.type";
    public static final String REGISTRY_SERVICE_ID   = PREFIX + ".registry.service-id";
    public static final String REGISTRY_SERVICE_HOST = PREFIX + ".registry.service-host";
    public static final String REGISTRY_SERVICE_PORT = PREFIX + ".registry.service-port";
    public static final String REGISTRY_SERVICE_INSTANCE_ID = PREFIX + ".registry.service-instance-id";
    public static final String REGISTRY_HEALTH_PATH  = PREFIX + ".registry.health-path";
    public static final String REGISTRY_META         = PREFIX + ".registry.meta.";

    // ── RPC（远程调用）──────────────────────────────────────
    public static final String RPC_CONNECT_TIMEOUT     = PREFIX + ".rpc.connect-timeout";
    public static final String RPC_REQUEST_TIMEOUT     = PREFIX + ".rpc.request-timeout";
    public static final String RPC_RETRY_MAX_ATTEMPTS  = PREFIX + ".rpc.retry.max-attempts";
    public static final String RPC_RETRY_BACKOFF_BASE  = PREFIX + ".rpc.retry.backoff-base";
    public static final String RPC_RETRY_BACKOFF_MAX   = PREFIX + ".rpc.retry.backoff-max";
    public static final String RPC_CB_ENABLED          = PREFIX + ".rpc.circuit-breaker.enabled";
    public static final String RPC_CB_FAILURE_THRESHOLD = PREFIX + ".rpc.circuit-breaker.failure-threshold";
    public static final String RPC_CB_OPEN_WINDOW      = PREFIX + ".rpc.circuit-breaker.open-window";
    public static final String RPC_RATE_LIMIT_ENABLED  = PREFIX + ".rpc.rate-limit.enabled";
    public static final String RPC_RATE_LIMIT_PER_SECOND = PREFIX + ".rpc.rate-limit.per-second";
    public static final String RPC_TRACE_ENABLED       = PREFIX + ".rpc.trace.enabled";

    // ── RPC / TLS ───────────────────────────────────────────
    public static final String RPC_TLS_KEY_STORE          = PREFIX + ".rpc.tls.key-store";
    public static final String RPC_TLS_KEY_STORE_PASSWORD = PREFIX + ".rpc.tls.key-store-password";
    public static final String RPC_TLS_TRUST_STORE        = PREFIX + ".rpc.tls.trust-store";
    public static final String RPC_TLS_TRUST_STORE_PASSWORD = PREFIX + ".rpc.tls.trust-store-password";

    // ── Health ─────────────────────────────────────────────
    public static final String HEALTH_ENABLED = PREFIX + ".health.enabled";

    // ── Region（共享）──────────────────────────────────────
    public static final String REGION = PREFIX + ".region";
}
```

## 8. core 与 freeway-ext 的边界

**core（`freeway-cloud`，零依赖，本方案全部 Phase）**：所有抽象接口 +
`XDefault` 生产级默认实现 + `@Marker` 后端/策略选择。ext 不是 core 的
"补完"，而是 freeway **选择不自建**的能力（需第三方服务/客户端）的
**可选替代**。

- core：`ServiceDiscoveryDefault`/`ServiceRegistryDefault`（进程内注册表）、
  `CloudHttpClientDefault`（JDK HttpClient）、`CloudConfigDefault`
  （WatchService 文件热重载）、`TracerDefault`/`MeterRegistryDefault`、
  `CircuitBreakerDefault`/`RateLimiterDefault`/`RetryerDefault`、
  `SecretStoreDefault`、`ObjectStorageDefault`（文件系统）、
  `TransportSecurityNone`（明文开发态）。
- ext（独立仓库，P5 及以后，每个 `.primary()` 覆盖对应默认）：Nacos/
  Consul/etcd/K8s 注册与配置、OTel 导出、S3、Vault/KMS、gRPC/WebClient
  传输、mTLS 密钥库证书、JWT/Opaque token 校验。

## 9. 实施阶段（对齐 implementation-plan Phase 0–8）

| Phase | 内容 |
|---|---|
| 0 | 脚手架：Maven 模块 + 根 pom 追加 + 包结构 + CloudModule + 子模块 + 注解 |
| 1 | 核心对象 + 本地默认（ServiceId/Endpoint/ServiceInstance/InvocationContext/CloudConfig + 全部 XDefault + `.primary()`） |
| 2 | 注册发现 + 远程调用（Registry/Discovery/Declaration/LoadBalancer + CloudHttpClient + 心跳 + trace 头注入 + 重试换实例） |
| 3 | 配置中心（CloudConfig 热刷新 + ConfigRef + ConfigChangedEvent + 动态 SymbolProvider） |
| 4 | 可观测性（Tracer + ScopedValue/MDC + W3C 传播 + /metrics + /health/live|ready） |
| 5 | 韧性（熔断/限流/重试 + CloudHttpClient 集成 + Advisor 注解可选） |
| 6 | 安全（PrincipalContext + 传播 + mTLS 抽象 + SecretStore SymbolSource 接线） |
| 7 | 对象存储（可选，不影响核心链路） |
| 8 | freeway-ext 适配器（另案） |

## 10. 明确不做

- 透明远程 bean / `@CloudClient` 接口代理 / `CloudExporter` 服务端导出 /
  `/rpc/*` 私有协议。
- classpath 扫描式自动注册（`ServiceLoader` 除外，可关）。
- 业务数据进入 `InvocationContext`；实例属性进入 `@Marker`。
- 第三方 SDK 进入 core。
- 分布式事务、分布式锁（协调型，超范围）；MQ/事件桥接（`EventBridge`
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
   （`InjectResolver` 一次性警告）。规避约定（`@Local`+`.primary()` 兜底）
   仍然成立。
5. **`ServiceIds.normalize` 包私有**：cloud 无法直接调用；守卫经
   `Binding.id()` 隐式生效，`ServiceId` 构造守卫语义对齐（§3.1）。
6. **测试框架**：根 pom `junit.version=6.1.3`（JUnit 6.x，非 5.12）。
7. **`ServiceId` 不造公开类型**：遵循 CLAUDE.md（"ServiceId is
   intentionally not a public type"）——serviceId 是普通字符串，守卫由
   ioc `ServiceIds.normalize`（绑定 id 隐式）承担，cloud 不引入独立
   `ServiceId` record（§3.1）。

## 12. 实施状态与后续工作

**core 已完成（2026-08-19，53 个测试全绿）**：Phase 0–7 全部落地——
脚手架、核心对象与本地默认、注册发现与远程调用、配置中心、可观测性、
韧性、安全、对象存储。实现细节与偏差见各 § 与 §11。

### 12.1 Phase 8（freeway-ext，后续另做）

云后端适配器，每个用 `.primary()` 覆盖 core 默认、`@Marker` 标记后端。
**必须为完整实现（非占位）**，遵循 freeway-ext 既有惯例（参照
`freeway-db-hikari`）；适配器是"可选替代"而非"补完"（§11.3 质量准则）。

| 模块 | 覆盖 SPI | 第三方依赖 | 建议顺序 |
|---|---|---|---|
| `freeway-cloud-nacos` | `ServiceRegistry` / `ServiceDiscovery` / `CloudConfig` | nacos-client | 1 |
| `freeway-cloud-k8s` | 同上 | fabric8 k8s-client | 2 |
| `freeway-cloud-consul` | 同上 | consul-client | 3 |
| `freeway-cloud-otel` | `Tracer` / `MeterRegistry`（OTLP / remote-write） | OpenTelemetry SDK | 4 |
| `freeway-cloud-s3` | `ObjectStorage` | AWS S3 SDK | 5 |
| `freeway-cloud-vault` | `SecretStore` | Vault client | 6 |
| `freeway-cloud-grpc` | `CloudHttpClient` 传输（`@Grpc`） | gRPC | 7 |

每个适配器交付：pom + 实现类（注册/心跳/反注册、配置拉取 + watch）+
模块绑定（`@Marker(Xxx)` + `.primary()`）+ 真实后端测试。测试后端策略
（testcontainers vs 本地单机）开工时定。

### 12.2 core 可选后续

- `@Retry` / `@CircuitBreak` / `@RateLimit` 注解 + `Advisor` 织入本地接口
  服务（§5.6 后期可选）。
- `ConfigRef` 与 `@Value` 动态注入的用法示例文档。
