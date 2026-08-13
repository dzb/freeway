# Freeway Cloud 设计方案

## 设计目标

为 Freeway 框架增加云原生能力：

- **零核心依赖**：`freeway-cloud` 模块（主仓库）零第三方依赖（SLF4J 除外）
- **适配器下沉**：云厂商 SDK、K8s Client 等依赖由 `freeway-ext` 承载
- **可脱离云运行**：所有接口提供本地默认实现（基于文件系统 / classpath / 内存）
- **渐进式接入**：每个能力一个子模块，按需安装

## 设计参考：现有模块内部结构

| 模式 | freeway-http | freeway-db |
|------|-------------|-----------|
| **公开接口** | `http/` 顶层 — `HttpEngine`, `HttpContext`, `WebServer` | `db/` 顶层 — `Database`, `Pool`, `Dialect`, `Orm` |
| **配置键** | `HttpConfigKeys` 集中常量类 | `DbConfigKeys` 集中常量类 |
| **实现隔离** | `engine/` 子包（含 `http2/`, `ws/`；HTTP/1.x 直接在 `engine/`） | `internal/` 子包（`DatabaseImpl`, `PoolDefault` 等） |
| **独立子系统** | `filter/`, `route/`, `sse/`, `websocket/`, `staticfile/` | `schema/`, `migration/` |
| **领域事件** | `event/` — `HttpServerStartedEvent`, `HttpExchangeEvent` | 无 |
| **独立构建器** | `WebServerBuilder` | `DatabaseBuilder` |
| **IoC 接线** | 仅 `HttpModule` | 仅 `DbModule` |
| **Primary 选择** | `HttpEngine` → `FreewayHttpEngine` | `Dialect` → `PostgresDialect` |
| **生命周期钩子** | `RuntimeHook(id="freeway.http.server")` | `RuntimeHook(id="freeway.db.migration").before("freeway.http.server")` |

## 模块依赖图（更新后）

```
freeway-commons         zero deps
 ├─ freeway-ioc         depends on commons
 │   ├─ freeway-boot    depends on ioc
 │   ├─ freeway-http    depends on ioc (+ commons transitive)
 │   ├─ freeway-flow    depends on ioc + commons
 │   └─ freeway-cloud   depends on ioc + commons   ← 新增
 │       └─ [扩展]      freeway-cloud-aws / freeway-cloud-k8s / freeway-cloud-grpc / freeway-cloud-otel
 └─ freeway-db          depends on commons (ioc optional)
```

## 包结构：`com.jujin.freeway.cloud`

```
cloud/
├── CloudConfigKeys.java          ← 集中配置键常量
├── CloudModule.java              ← 聚合所有子模块
│
├── config/                       ← ConfigStore 子系统
│   ├── ConfigStore.java          ← 接口
│   ├── ConfigSubscription.java   ← watch 句柄
│   ├── ConfigStoreLoader.java    ← ConfigStore → ConfigLoader 适配器
│   ├── CloudConfigModule.java    ← IoC 接线
│   └── internal/
│       └── LocalConfigStore.java ← 基于 classpath 文件的默认实现
│
├── secret/                       ← SecretStore 子系统
│   ├── SecretStore.java          ← 接口
│   ├── SecretSymbolSource.java   ← SecretStore → SymbolSource 适配
│   ├── CloudSecretModule.java    ← IoC 接线
│   └── internal/
│       └── LocalSecretStore.java ← 基于 env/file 的默认实现
│
├── storage/                      ← ObjectStorage 子系统
│   ├── ObjectStorage.java        ← 接口
│   ├── ObjectMetadata.java       ← record
│   ├── ObjectEntry.java          ← record
│   ├── PutResult.java            ← record
│   ├── StorageException.java     ← 异常
│   ├── ObjectStorageBuilder.java ← 独立构建器
│   ├── CloudStorageModule.java   ← IoC 接线
│   └── internal/
│       └── FileSystemStorage.java ← 基于文件系统的默认实现
│
├── discovery/                    ← 服务发现与注册
│   ├── ServiceDiscovery.java     ← 接口
│   ├── ServiceDeclaration.java   ← 扩展点：声明"要注册什么"
│   ├── ServiceInstance.java      ← record
│   ├── ServiceRegistry.java      ← 接口
│   ├── CloudDiscoveryModule.java ← IoC 接线
│   └── internal/
│       ├── ConfigServiceDiscovery.java  ← 从配置文件读取
│       └── InMemoryServiceRegistry.java ← 内存注册表
│
├── rpc/                          ← 远程调用（ServiceDiscovery 的消费者）
│   ├── RpcClient.java            ← 接口
│   ├── RpcRequest.java           ← record: method + path + headers + body
│   ├── RpcResponse.java          ← record: status + headers + body
│   ├── RpcException.java         ← 调用失败异常
│   ├── CloudRpcModule.java       ← IoC 接线
│   ├── trace/
│   │   ├── TraceContext.java     ← 跨服务传递的追踪信息
│   │   └── Tracer.java           ← 追踪器接口（默认 noop）
│   ├── policy/
│   │   ├── RetryPolicy.java      ← 重试策略接口 + 内置策略
│   │   └── CircuitBreaker.java   ← 熔断器接口 + 默认实现
│   └── internal/
│       ├── HttpRpcClient.java    ← JDK HttpClient 默认实现
│       └── MdcTracer.java        ← SLF4J MDC 追踪实现
│
├── health/                       ← 云健康检查
│   ├── CloudHealthContributor.java ← 扩展点接口
│   ├── HealthResult.java         ← record
│   ├── CloudHealthModule.java    ← 聚合健康检查
│   └── internal/
│       └── CloudAwareHealthCheck.java ← 覆盖 HealthCheck.Default
│
└── event/                        ← 云领域事件
    ├── ConfigChangedEvent.java   ← ConfigStore 变更通知
    ├── ObjectStoredEvent.java    ← ObjectStorage 写入触发
    └── ObjectDeletedEvent.java   ← ObjectStorage 删除触发
```

---

## 完整能力矩阵

| 子系统 | 包路径 | 核心类型 | 默认实现 | Phase 3 适配器 |
|--------|--------|---------|---------|---------------|
| 配置管理 | `config/` | `ConfigStore`, `ConfigSubscription` | `LocalConfigStore` | `AwsSsmConfigStore`, `K8sConfigMapStore` |
| 密钥管理 | `secret/` | `SecretStore`, `SecretSymbolSource` | `LocalSecretStore` | `AwsSecretsManagerStore`, `K8sSecretStore` |
| 对象存储 | `storage/` | `ObjectStorage`, `ObjectMetadata`, `PutResult` | `FileSystemStorage` | `AwsS3Storage` |
| 服务发现 | `discovery/` | `ServiceDiscovery`, `ServiceInstance` | `ConfigServiceDiscovery` | `K8sDnsDiscovery` |
| 服务注册 | `discovery/` | `ServiceRegistry`, `ServiceDeclaration` | `InMemoryServiceRegistry` | Consul/Eureka 适配器 |
| 远程调用 | `rpc/` | `RpcClient`, `RpcRequest`, `RpcResponse` | `HttpRpcClient` | `GrpcRpcClient` |
| 分布式追踪 | `rpc/trace/` | `Tracer`, `TraceContext` | `MdcTracer` | `OtelTracer` |
| 韧性/容错 | `rpc/policy/` | `RetryPolicy`, `CircuitBreaker` | `ExponentialBackoff` + 默认熔断器 | 可插拔替换 |
| 健康检查 | `health/` | `CloudHealthContributor`, `HealthResult` | `CloudAwareHealthCheck` | 各适配器贡献 |
| 领域事件 | `event/` | `ConfigChangedEvent`, `ObjectStoredEvent`, `ObjectDeletedEvent` | — | — |

## 设计原则

### 1. `internal/` 包隔离实现

`internal/` 下的类不作为公开 API 承诺兼容性。应用代码应依赖接口而非实现类。遵循 `freeway-db` 中 `PoolDefault`、`DatabaseImpl` 不公开的模式。

### 2. IoC 接线收敛到子模块类

每个子系统有独立的 `*Module` 类 — 该类是唯一持有 IoC 感知的代码。核心接口和 record 本身不依赖 Container。

```java
public final class CloudConfigModule implements ModuleEx {
    @Override
    public void bind(Binder b) {
        b.bind(ConfigStore.class)
            .to(LocalConfigStore.class)
            .id("local")
            .primary();

        b.bind(ConfigStoreLoader.class)
            .to(container -> new ConfigStoreLoader(container.get(ConfigStore.class)));

        b.contribute(RuntimeHook.class)
            .add("freeway.cloud.config-store", new RuntimeHook() {
                @Override
                public void start(Container c) { /* 连接初始化 */ }
                @Override
                public void stop(Container c) { /* 断开清理 */ }
            })
            .before("freeway.http.server");
    }
}
```

### 3. CloudModule 聚合子模块

`binder.install()` 内置按 `module.getClass()` 去重，可安全混用子模块和聚合模块：

```java
@Marker(Builtin.class)
public final class CloudModule implements ModuleEx {
    @Override
    public void bind(Binder b) {
        b.install(new CloudConfigModule());
        b.install(new CloudSecretModule());
        b.install(new CloudStorageModule());
        b.install(new CloudDiscoveryModule());
        b.install(new CloudRpcModule());
        b.install(new CloudHealthModule());
    }
}
```

### 4. 同步 API 设计

所有接口使用同步方法签名，遵循 `Database`、`Pool`、`EventBus.publish()` 的模式。Virtual thread 处理并发，调用方如需异步编排在外层包裹：

```java
public interface ObjectStorage {
    Optional<byte[]> get(String bucket, String key) throws StorageException;
    PutResult put(String bucket, String key, byte[] data, ObjectMetadata metadata) throws StorageException;
    void delete(String bucket, String key) throws StorageException;
    List<ObjectEntry> list(String bucket, String prefix) throws StorageException;
    Optional<URL> presignedUrl(String bucket, String key, Duration ttl);
}
```

### 5. 独立构建器

每个主要抽象提供 `*Builder`，使其可在无 IoC 容器环境下使用，遵循 `DatabaseBuilder`、`WebServerBuilder` 模式：

```java
var storage = ObjectStorageBuilder.localFs(Path.of("/data")).build();
storage.put("my-bucket", "key.txt", data, new ObjectMetadata("text/plain", data.length, Map.of()));
```

### 6. 领域事件

Cloud 操作产生领域事件，通过 EventBus 发布：

```java
public record ConfigChangedEvent(String key, String oldValue, String newValue) {}
public record ObjectStoredEvent(String bucket, String key, long size, String etag) {}
public record ObjectDeletedEvent(String bucket, String key) {}
```

Discovery 和 RPC 不产生领域事件——Discovery 是拉取操作，RPC 调用频率过高不适合走 EventBus。

---

## 子系统设计

### 一、ConfigStore —— 外部配置

```java
public interface ConfigStore {
    Optional<String> get(String key);
    Map<String, String> asMap();

    /** 订阅 key 变化。实现可以忽略（返回 NOOP）。 */
    default ConfigSubscription watch(String key, Consumer<String> listener) {
        return ConfigSubscription.NOOP;
    }
}

@FunctionalInterface
public interface ConfigSubscription extends AutoCloseable {
    ConfigSubscription NOOP = () -> {};
    @Override void close();
}
```

`LocalConfigStore` 不实现 watch（NOOP）；`K8sConfigMapStore` 利用 K8s watch API；`AwsSsmConfigStore` 可选择轮询或 EventBridge 通知。

### 二、SecretStore —— 密钥管理

与 ConfigStore 分离的核心原因：

| 维度 | ConfigStore | SecretStore |
|------|------------|-------------|
| `asMap()` | 安全 | 禁止实现——密钥不可批量暴露 |
| 缓存策略 | 长缓存 + watch 通知 | TTL 控制支持轮换 |
| 审计日志 | 普通级别 | 安全级别（`get()` 需额外审计） |
| 适配器组合 | 可 K8s ConfigMap + AWS Secrets Manager 混用 | 来源独立 |
| 默认值 | 允许 fallback 到本地 | 不允许——密钥必须显式配置 |

```java
public interface SecretStore {
    Optional<String> get(String key);
    default Optional<byte[]> getBytes(String key) {
        return get(key).map(s -> s.getBytes(StandardCharsets.UTF_8));
    }
}
```

`SecretSymbolSource` 包装 `SecretStore` 为 `SymbolSource` 的 fallback，使 `@Symbol("db.password")` 可解析密钥。

### 三、ObjectStorage —— 对象存储

```java
public interface ObjectStorage {
    Optional<byte[]> get(String bucket, String key) throws StorageException;
    PutResult put(String bucket, String key, byte[] data, ObjectMetadata metadata) throws StorageException;
    void delete(String bucket, String key) throws StorageException;
    List<ObjectEntry> list(String bucket, String prefix) throws StorageException;
    Optional<URL> presignedUrl(String bucket, String key, Duration ttl);
}

public record ObjectMetadata(String contentType, long contentLength, Map<String, String> userMetadata) {}
public record ObjectEntry(String key, long contentLength, Instant lastModified, String etag) {}
public record PutResult(String etag, String versionId) {}
```

### 四、ServiceDiscovery + ServiceRegistry

```java
public interface ServiceDiscovery {
    List<ServiceInstance> getInstances(String serviceId);
    default Optional<ServiceInstance> getInstance(String serviceId) {
        return getInstances(serviceId).stream().findFirst();
    }
}

public record ServiceInstance(
    String serviceId, String host, int port, boolean secure,
    Map<String, String> metadata, URI uri
) {
    public URI uri() { return URI.create("%s://%s:%d".formatted(secure ? "https" : "http", host, port)); }
}

public interface ServiceRegistry {
    void register(ServiceInstance instance);
    void deregister(ServiceInstance instance);
}
```

### 五、ServiceDeclaration —— "哪些服务可以注册"

自注册逻辑不能硬编码"只有一个 HTTP 端点"。实际可能有 HTTP 端点、gRPC 端点、自定义协议端点、多端口场景。需要扩展机制让**任何模块**声明，由 `CloudDiscoveryModule` 统一收集注册。

```java
@FunctionalInterface
public interface ServiceDeclaration {
    /** 从 Container 构造此次启动需要注册的 ServiceInstance。
     *  在 HTTP server 启动后调用（host:port 已确定）。 */
    ServiceInstance resolve(Container container);
}
```

各模块声明自己的端点：

```java
// HTTP 端点
binder.contribute(ServiceDeclaration.class)
    .add("http", (ServiceDeclaration) container -> {
        WebServer server = container.get(WebServer.class);
        String serviceId = symbols.resolve(CloudConfigKeys.REGISTRY_SERVICE_ID,
            symbols.resolve("freeway.app.name", "freeway-app"));
        return new ServiceInstance(serviceId, server.host(), server.port(), false, Map.of(), null);
    });

// gRPC 端点（未来 freeway-cloud-grpc）
binder.contribute(ServiceDeclaration.class)
    .add("grpc", (ServiceDeclaration) container -> {
        GrpcServer server = container.get(GrpcServer.class);
        return new ServiceInstance("grpc-endpoint", server.host(), server.port(), false, Map.of(), null);
    });

// 用户自定义
binder.contribute(ServiceDeclaration.class)
    .add("admin", (ServiceDeclaration) container -> {
        return new ServiceInstance("admin-api", "0.0.0.0", 9091, true, Map.of(), null);
    });
```

`CloudDiscoveryModule` 的注册 Hook 统一收集并注册。`ServiceDeclaration` 是 `@FunctionalInterface`——命名已在 `contribute().add("id", ...)` 中提供，接口保持最小化。

静态第三方服务（PostgreSQL、Redis）由配置指定地址，不走 Discovery——只有动态分配地址的服务才需要注册。

### 六、RpcClient —— 远程调用

**调用链路：**

```
Service A (caller, virtual thread)           Service B (callee, 普通 Freeway 应用)
──────────────────────────────               ──────────────────────────────

routeHandler.handle(ctx)
  ├─ rpc.call("user-service", request)
  │     ├─ ① discovery.getInstances("user-service")
  │     ├─ ② 选实例（可插拔策略）
  │     ├─ ③ httpClient.send(GET http://host:port/path)   ────→  WebServer
  │     │                                                       routeHandler
  │     │                                                    ctx.sendJson(200, user)
  │     │  ←─── HTTP 200 + JSON ───
  │     ├─ ④ RpcResponse(200, headers, body)
  │     ├─ ⑤ response.bodyAs(User.class) → User
  │     └─ return user
  └─ render template
```

被调方不需要任何特殊代码——它就是普通的 Freeway HTTP 应用。调用在 virtual thread 中同步阻塞，无成本。

**接口设计：**

```java
public interface RpcClient {
    RpcResponse call(String serviceId, RpcRequest request);
}

public record RpcRequest(String method, String path, Map<String, String> headers, byte[] body) {
    public static RpcRequest get(String path) { return new RpcRequest("GET", path, Map.of(), null); }
    public static RpcRequest post(String path, byte[] body, String contentType) {
        return new RpcRequest("POST", path, Map.of("Content-Type", contentType), body);
    }
}

public record RpcResponse(int status, Map<String, List<String>> headers, byte[] body) {
    public <T> T bodyAs(Class<T> type, JsonCodec codec) { ... }
    public boolean is2xx() { return status >= 200 && status < 300; }
}
```

**不做 typed proxy**：不用 `@RpcClient` 注解 + 接口代理，保持显式声明原则。调用的每一步对开发者可见：指定服务名、构造 request、拿到 response、解码 body。进阶封装由应用层自行构建。

**默认实现**（零额外依赖，基于 JDK `java.net.http.HttpClient`）：

```java
final class HttpRpcClient implements RpcClient {
    private final ServiceDiscovery discovery;
    private final JsonCodec jsonCodec;
    private final HttpClient http;
}
```

**CloudRpcModule 接线：**

```java
b.bind(RpcClient.class)
    .to(container -> new HttpRpcClient(
        container.get(ServiceDiscovery.class),
        container.get(JsonCodec.class)))
    .id("http");
```

### 七、分布式追踪

**问题**：RpcClient 跨服务调用后，Service A 的日志和 Service B 的日志无法关联。

**设计**：`Tracer` 接口 + `TraceContext` record。默认实现 `MdcTracer` 将 traceId 写入 SLF4J MDC，与 Freeway 已有的 `JULMDCAdapter` 兼容。外接 OpenTelemetry 走 freeway-ext 适配器。

```java
public record TraceContext(String traceId, String spanId, String parentSpanId) {
    public static final String HEADER_TRACE_ID = "traceparent";

    public static Optional<TraceContext> fromHeaders(Map<String, String> headers) { ... }
    public Map<String, String> toHeaders() { ... }
    public static TraceContext root() { ... }
    public TraceContext child() { ... }
}

@FunctionalInterface
public interface Tracer {
    Span start(String name, TraceContext parent);
    Tracer NOOP = (name, parent) -> Span.NOOP;

    interface Span extends AutoCloseable {
        Span NOOP = () -> {};
        void addTag(String key, String value);
        void addError(Throwable t);
        @Override void close();
    }
}
```

**MdcTracer（默认实现，零外部依赖）**：

```java
final class MdcTracer implements Tracer {
    @Override
    public Span start(String name, TraceContext parent) {
        MDC.put("traceId", parent.traceId());
        MDC.put("spanId", parent.spanId());
        return new MdcSpan();
    }
    record MdcSpan() implements Span {
        @Override public void close() { MDC.remove("traceId"); MDC.remove("spanId"); }
    }
}
```

**HttpRpcClient 集成**：提取或创建 TraceContext → 注入到下游请求头 → 创建 client span → 调用 → 记录结果。

**调用链效果：**

```
Service A                         Service B
traceId: abc123                   收到 traceparent: abc123/span-B-id
MDC: {traceId: abc123}           MDC: {traceId: abc123}
  ├─ rpc.call("svc-b", req)         ├─ 日志: "GET /api/users/42" [traceId=abc123]
  │   注入 traceparent               │
  │   ──────────────────────→       │
  │   ←─── HTTP 500 ────────        ├─ 日志: "SQL error" [traceId=abc123]
  ├─ 日志: "RPC failed: 500" [traceId=abc123]
```

### 八、韧性 / 容错

`RetryPolicy` 和 `CircuitBreaker` 让 RPC 在生产环境可用。默认实现使用 JDK 内置并发原语（`synchronized` + `volatile`），零外部依赖。

**RetryPolicy：**

```java
public interface RetryPolicy {
    /** attempt 从 0 开始计数 */
    boolean shouldRetry(int attempt, RpcException failure);
    long backoffMillis(int attempt);

    // 默认：最多重试 3 次，指数退避 100ms→200ms→400ms，上限 5s
    RetryPolicy DEFAULT = new ExponentialBackoff(3, 100, 5000);
    RetryPolicy NO_RETRY = (attempt, failure) -> false;

    record ExponentialBackoff(int maxRetries, long baseMillis, long maxMillis)
            implements RetryPolicy {
        @Override
        public boolean shouldRetry(int attempt, RpcException failure) {
            return attempt < maxRetries && failure.isRetryable();
            // 连接失败/超时可重试；HTTP 4xx 不重试
        }
        @Override
        public long backoffMillis(int attempt) {
            return Math.min(baseMillis * (1L << attempt), maxMillis);
        }
    }
}
```

**CircuitBreaker：**

```java
public interface CircuitBreaker {
    enum State { CLOSED, OPEN, HALF_OPEN }

    State state();
    boolean allowRequest();  // OPEN 时返回 false
    void onSuccess();        // HALF_OPEN 转 CLOSED
    void onFailure();        // 累计到阈值转 OPEN

    static CircuitBreaker create(int failureThreshold, Duration openWindow) { ... }
    CircuitBreaker NOOP = /* always CLOSED */;
}
```

**RpcException：**

```java
public class RpcException extends RuntimeException {
    private final boolean retryable;
    // 连接失败/超时 → retryable = true
    // HTTP 4xx → retryable = false
    // HTTP 5xx → retryable = true（但 CircuitBreaker 累计失败）
}
```

**HttpRpcClient.call() 内部编排：**

```java
public RpcResponse call(String serviceId, RpcRequest request) {
    int attempt = 0;
    while (true) {
        try {
            if (!breaker.allowRequest())
                throw new RpcException("Circuit breaker OPEN for " + serviceId, false);
            RpcResponse resp = doSingleCall(serviceId, injectTrace(request));
            breaker.onSuccess();
            return resp;
        } catch (RpcException e) {
            breaker.onFailure();
            if (!retryPolicy.shouldRetry(attempt, e)) throw e;
            sleep(retryPolicy.backoffMillis(attempt));
            attempt++;
        }
    }
}
```

**CloudRpcModule 接线**（可通过配置覆盖，也可通过 `.primary()` 被适配器替换）：

```java
b.bind(RetryPolicy.class).to(RetryPolicy.DEFAULT);
b.bind(CircuitBreaker.class).to(container -> CircuitBreaker.create(5, Duration.ofSeconds(30)));
b.bind(Tracer.class).to(MdcTracer.class).id("mdc");
```

`Tracer`、`RetryPolicy`、`CircuitBreaker` 与传输无关——gRPC 适配器可复用同一策略实例。

### 九、服务生命周期

CloudDiscoveryModule 注册两个 RuntimeHook——一个在 HTTP server 前（连接注册中心客户端），一个在 HTTP server 后（收集 `ServiceDeclaration` 并执行自注册）：

```java
// CloudDiscoveryModule.java
public void bind(Binder b) {
    b.bind(ServiceDiscovery.class).to(...)
    b.bind(ServiceRegistry.class).to(...)

    // Hook 1: 连接注册中心（before HTTP server）
    b.contribute(RuntimeHook.class)
        .add("freeway.cloud.discovery", new RuntimeHook() {
            @Override public void start(Container c) { /* 初始化客户端连接 */ }
            @Override public void stop(Container c) { /* 关闭连接 */ }
        })
        .before("freeway.http.server");

    // Hook 2: 收集 ServiceDeclaration，统一注册（after HTTP server）
    b.contribute(RuntimeHook.class)
        .add("freeway.cloud.registry", new RuntimeHook() {
            private final List<ServiceInstance> registered = new ArrayList<>();

            @Override
            public void start(Container c) {
                ServiceRegistry registry = c.get(ServiceRegistry.class);
                for (ServiceDeclaration decl : c.extension(ServiceDeclaration.class).all()) {
                    ServiceInstance instance = decl.resolve(c);
                    registry.register(instance);
                    registered.add(instance);
                }
            }
            @Override
            public void stop(Container c) {
                ServiceRegistry registry = c.get(ServiceRegistry.class);
                for (ServiceInstance instance : registered) {
                    try { registry.deregister(instance); }
                    catch (Exception ex) { LOG.warn("Deregister failed", ex); }
                }
                registered.clear();
            }
        })
        .after("freeway.http.server");
}
```

**serviceId 来源优先级**：
1. 配置 `freeway.cloud.registry.service-id`
2. `freeway.app.name`（通用应用标识）

**运行时调用：**

```java
class UserController {
    @Inject RpcClient rpc;

    public UserProfile getProfile(long userId) {
        var resp = rpc.call("user-service", RpcRequest.get("/api/users/" + userId));
        return resp.bodyAs(UserProfile.class, jsonCodec);
    }
}
```

`HttpRpcClient` 内部流程：`discovery.getInstances(serviceId)` → 取实例 → 构造 JDK `HttpRequest` → 发送 → 封装为 `RpcResponse`。实例不可达时从 discovery 列表重试下一个。

### 十、健康检查

```java
public interface CloudHealthContributor {
    String name();          // e.g., "s3", "configmap"
    HealthResult check();
}

public record HealthResult(boolean healthy, String detail) {
    public static HealthResult ok() { return new HealthResult(true, ""); }
    public static HealthResult unhealthy(String detail) {
        return new HealthResult(false, Objects.requireNonNull(detail));
    }
}
```

`CloudAwareHealthCheck` 聚合所有 `CloudHealthContributor`，覆盖 `HealthCheck.Default`：

```json
{
  "status": "ok",
  "cloud": {
    "config-store": { "healthy": true },
    "secret-store": { "healthy": true },
    "object-storage": { "healthy": true, "detail": "bucket 'assets' reachable" },
    "discovery": { "healthy": false, "detail": "consul unreachable" }
  }
}
```

各适配器贡献自己的健康检查：

```java
b.contribute(CloudHealthContributor.class)
    .add("aws-s3", () -> {
        try { s3.listBuckets(); return HealthResult.ok(); }
        catch (Exception e) { return HealthResult.unhealthy(e.getMessage()); }
    });
```

---

## 配置键：CloudConfigKeys

```java
public final class CloudConfigKeys {
    private CloudConfigKeys() {}
    static final String PREFIX = "freeway.cloud";

    // ── Config Store ─────────────────────────────────────
    public static final String CONFIG_STORE_TYPE = PREFIX + ".config-store.type";

    // ── Secret Store ─────────────────────────────────────
    public static final String SECRET_STORE_TYPE = PREFIX + ".secret-store.type";

    // ── Object Storage ───────────────────────────────────
    public static final String STORAGE_TYPE      = PREFIX + ".storage.type";
    public static final String STORAGE_BUCKET    = PREFIX + ".storage.bucket";
    public static final String STORAGE_BASE_PATH = PREFIX + ".storage.base-path";
    public static final String STORAGE_REGION    = PREFIX + ".storage.region";
    public static final String STORAGE_ENDPOINT  = PREFIX + ".storage.endpoint";

    // ── Service Discovery ────────────────────────────────
    public static final String DISCOVERY_TYPE        = PREFIX + ".discovery.type";
    public static final String REGISTRY_TYPE         = PREFIX + ".registry.type";
    public static final String REGISTRY_SERVICE_ID   = PREFIX + ".registry.service-id";
    public static final String REGISTRY_SERVICE_HOST = PREFIX + ".registry.service-host";
    public static final String REGISTRY_SERVICE_PORT = PREFIX + ".registry.service-port";
    public static final String REGISTRY_HEALTH_PATH  = PREFIX + ".registry.health-path";
    public static final String REGISTRY_META         = PREFIX + ".registry.meta.";

    // ── RPC ───────────────────────────────────────────────
    public static final String RPC_TYPE            = PREFIX + ".rpc.type";
    public static final String RPC_CONNECT_TIMEOUT = PREFIX + ".rpc.connect-timeout";
    public static final String RPC_REQUEST_TIMEOUT = PREFIX + ".rpc.request-timeout";

    // ── RPC / Retry ────────────────────────────────────────
    public static final String RPC_RETRY_MAX_ATTEMPTS = PREFIX + ".rpc.retry.max-attempts";
    public static final String RPC_RETRY_BACKOFF_BASE  = PREFIX + ".rpc.retry.backoff-base";
    public static final String RPC_RETRY_BACKOFF_MAX   = PREFIX + ".rpc.retry.backoff-max";

    // ── RPC / Circuit Breaker ──────────────────────────────
    public static final String RPC_CB_ENABLED           = PREFIX + ".rpc.circuit-breaker.enabled";
    public static final String RPC_CB_FAILURE_THRESHOLD = PREFIX + ".rpc.circuit-breaker.failure-threshold";
    public static final String RPC_CB_OPEN_WINDOW       = PREFIX + ".rpc.circuit-breaker.open-window";

    // ── RPC / Trace ────────────────────────────────────────
    public static final String RPC_TRACE_ENABLED = PREFIX + ".rpc.trace.enabled";
    public static final String RPC_TRACE_TYPE    = PREFIX + ".rpc.trace.type";  // mdc, otel

    // ── Health ───────────────────────────────────────────
    public static final String HEALTH_ENABLED = PREFIX + ".health.enabled";

    // ── Region (shared) ──────────────────────────────────
    public static final String REGION = PREFIX + ".region";
}
```

---

## 关键设计决策

| 决策 | 结论 | 依据 |
|------|------|------|
| ConfigStore vs SecretStore | **分离** | 类型安全区分、缓存正交、安全审计隔离、适配器来源解耦 |
| ObjectStorage 异步 | **同步** | 遵循 Database/Pool 模式，virtual thread 处理并发 |
| `internal/` 隔离 | **是** | 遵循 freeway-db 实现与接口分离 |
| 子模块粒度 | **每抽象一模块** | 6 子模块 + CloudModule 聚合，按需安装 |
| RPC 抽象层次 | **路径级调用** | 不做 typed proxy，显式 request/response |
| 分布式追踪 | **Tracer + MDC** | 默认与 JUL MDC 兼容，OpenTelemetry 走适配器 |
| 韧性/容错 | **RetryPolicy + CircuitBreaker** | 生产 RPC 必需，JDK 内置并发原语 |
| 服务注册 | **ServiceDeclaration 扩展点** | 不限死 HTTP 端点，各模块声明 |
| 领域事件 | **仅存储和配置** | Discovery 拉取、RPC 高频不合适 |
| 健康检查 | **扩展点模式** | contribute() 收集，CloudAwareHealthCheck 聚合 |

---

## RuntimeHook 排序拓扑

```
Start:
                                before:
freeway.cloud.config-store  ──┐
freeway.cloud.secret-store  ──┤
freeway.cloud.storage       ──┤ freeway.http.server
freeway.cloud.discovery     ──┘
                                │
freeway.http.server         ────┘  (WebServer 启动，host:port 已知)
                                │
                                after:
freeway.cloud.registry      ────┘  (收集 ServiceDeclaration，register self)

Stop (reverse order):
freeway.cloud.registry      ── deregister self（先摘除流量，再关服务器）
freeway.http.server         ── stop server
freeway.cloud.*             ── close connections
```

关键约束：
- `freeway.cloud.registry` **必须在** `freeway.http.server` **之后**——服务地址只有 HTTP 服务器启动后才知道
- `freeway.cloud.registry` **必须在** `freeway.http.server` **之前停止**——先注销再关服务器
- `freeway.cloud.rpc` **不需要 RuntimeHook**——RpcClient 是按需调用的，不维护持久连接
- `freeway.cloud.discovery` 的 hook 只管理客户端连接，不执行注册操作

---

## 文件清单

### Phase 1：freeway-cloud 核心模块（38 个 Java 源文件）

```
freeway-cloud/pom.xml

src/main/java/com/jujin/freeway/cloud/
├── CloudConfigKeys.java
├── CloudModule.java
├── config/
│   ├── ConfigStore.java
│   ├── ConfigSubscription.java
│   ├── ConfigStoreLoader.java
│   ├── CloudConfigModule.java
│   └── internal/LocalConfigStore.java
├── secret/
│   ├── SecretStore.java
│   ├── SecretSymbolSource.java
│   ├── CloudSecretModule.java
│   └── internal/LocalSecretStore.java
├── storage/
│   ├── ObjectStorage.java
│   ├── ObjectMetadata.java
│   ├── ObjectEntry.java
│   ├── PutResult.java
│   ├── StorageException.java
│   ├── ObjectStorageBuilder.java
│   ├── CloudStorageModule.java
│   └── internal/FileSystemStorage.java
├── discovery/
│   ├── ServiceDiscovery.java
│   ├── ServiceDeclaration.java
│   ├── ServiceInstance.java
│   ├── ServiceRegistry.java
│   ├── CloudDiscoveryModule.java
│   └── internal/
│       ├── ConfigServiceDiscovery.java
│       └── InMemoryServiceRegistry.java
├── rpc/
│   ├── RpcClient.java
│   ├── RpcRequest.java
│   ├── RpcResponse.java
│   ├── RpcException.java
│   ├── CloudRpcModule.java
│   ├── trace/
│   │   ├── TraceContext.java
│   │   └── Tracer.java
│   ├── policy/
│   │   ├── RetryPolicy.java
│   │   └── CircuitBreaker.java
│   └── internal/
│       ├── HttpRpcClient.java
│       └── MdcTracer.java
├── health/
│   ├── CloudHealthContributor.java
│   ├── HealthResult.java
│   ├── CloudHealthModule.java
│   └── internal/CloudAwareHealthCheck.java
└── event/
    ├── ConfigChangedEvent.java
    ├── ObjectStoredEvent.java
    └── ObjectDeletedEvent.java
```

### Phase 1 修改文件

- 父 `pom.xml` ← 添加 `<module>freeway-cloud</module>`
- `CLAUDE.md` ← 更新模块依赖图

### Phase 3：freeway-ext 适配器

```
freeway-cloud-aws/
  → AwsSsmConfigStore, AwsSecretsManagerStore, AwsS3Storage, AwsCloudModule

freeway-cloud-k8s/
  → K8sConfigMapStore, K8sSecretStore, K8sDnsDiscovery, K8sCloudModule

freeway-cloud-grpc/
  → GrpcRpcClient, GrpcCloudModule（覆盖 RpcClient，引入 protobuf + gRPC）

freeway-cloud-otel/
  → OtelTracer（覆盖 Tracer，引入 OpenTelemetry SDK）
```

---

## 验证

```bash
# Phase 1 全量测试
mvn -pl freeway-cloud -am test

# 默认实现单元测试
mvn -pl freeway-cloud -am test -Dtest=LocalConfigStoreTest
mvn -pl freeway-cloud -am test -Dtest=LocalSecretStoreTest
mvn -pl freeway-cloud -am test -Dtest=FileSystemStorageTest
mvn -pl freeway-cloud -am test -Dtest=ConfigServiceDiscoveryTest

# IoC 接线测试
mvn -pl freeway-cloud -am test -Dtest=CloudModuleTest
mvn -pl freeway-cloud -am test -Dtest=HttpRpcClientTest
mvn -pl freeway-cloud -am test -Dtest=MdcTracerTest
mvn -pl freeway-cloud -am test -Dtest=RetryPolicyTest
mvn -pl freeway-cloud -am test -Dtest=CircuitBreakerTest
mvn -pl freeway-cloud -am test -Dtest=CloudHealthModuleTest

# Phase 3 适配器编译验证
cd freeway-ext && mvn -pl freeway-cloud-aws compile
cd freeway-ext && mvn -pl freeway-cloud-k8s compile
```

---

## 使用示例

```java
// Dev 环境：使用本地默认实现
FreewayApp.run(new CloudModule(), new HttpModule());

// AWS Prod：AwsCloudModule 通过 .primary() 覆盖默认
FreewayApp.run(new CloudModule(), new AwsCloudModule(), new HttpModule());

// K8s Prod
FreewayApp.run(new CloudModule(), new K8sCloudModule(), new HttpModule());

// 按需引入（仅使用 ConfigStore + SecretStore）
FreewayApp.run(new CloudConfigModule(), new CloudSecretModule());

// 运行时 RPC 调用
@Inject RpcClient rpc;

public UserProfile getProfile(long userId) {
    var resp = rpc.call("user-service", RpcRequest.get("/api/users/" + userId));
    return resp.bodyAs(UserProfile.class, jsonCodec);
}
```
