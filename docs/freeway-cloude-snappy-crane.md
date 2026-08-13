# Freeway Cloud — 细化设计方案

## Context

为 Freeway 框架增加云原生能力。设计目标：零核心依赖的接口层（`freeway-cloud`），第三方 SDK 的适配器模块（`freeway-ext`），可脱离云运行的本地默认实现。

本次迭代在 [docs/CLOUD-DESIGN.md](/Users/apple/Projects/freeway-2/docs/CLOUD-DESIGN.md) 粗粒度方案基础上，进一步细化 `freeway-cloud` 内部的职责切分、类型分发和实现隔离。

## 设计参考：现有多模块内部结构

两个成熟模块的内部组织模式：

| 模式 | freeway-http | freeway-db |
|------|-------------|-----------|
| **公开接口** | 顶层 `http/` 包 — `HttpEngine`, `HttpContext`, `WebServer` | 顶层 `db/` 包 — `Database`, `Pool`, `Dialect`, `Orm` |
| **配置键** | `HttpConfigKeys` — 集中常量类 | `DbConfigKeys` — 集中常量类 |
| **实现隔离** | `engine/` 子包（含 `http2/`, `ws/`；HTTP/1.x 直接在 `engine/`） | `internal/` 子包（`DatabaseImpl`, `PoolDefault`, 等） |
| **独立子系统** | `filter/`, `route/`, `sse/`, `websocket/`, `staticfile/` | `schema/`, `migration/` |
| **领域事件** | `event/` 子包 — `HttpServerStartedEvent`, `HttpExchangeEvent` | 无（未采用事件模式） |
| **独立构建器** | `WebServerBuilder` | `DatabaseBuilder` |
| **IoC 接线** | 仅 `HttpModule` | 仅 `DbModule` |
| **Primary 选择** | `HttpEngine` → `FreewayHttpEngine` (builtin) | `Dialect` → `PostgresDialect` (postgresql, primary) |
| **生命周期钩子** | `RuntimeHook` with id `"freeway.http.server"` | `RuntimeHook` with id `"freeway.db.migration"`, `.before("freeway.http.server")` |
| **子模块组合** | 无（单模块覆盖所有） | 无（单模块覆盖所有） |

## freeway-cloud 包结构设计

### 总包：`com.jujin.freeway.cloud`

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
│   ├── ObjectStorageBuilder.java ← 独立构建器
│   ├── CloudStorageModule.java   ← IoC 接线
│   └── internal/
│       └── FileSystemStorage.java ← 基于文件系统的默认实现
│
├── discovery/                    ← 服务发现与注册
│   ├── ServiceDiscovery.java     ← 接口
│   ├── ServiceDeclaration.java   ← 扩展点：声明"我要注册什么"
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
│       └── HttpRpcClient.java    ← 基于 JDK HttpClient 的默认实现
│
├── health/                       ← 云健康检查
│   ├── CloudHealthContributor.java ← 扩展点接口
│   ├── CloudHealthModule.java    ← 聚合健康检查
│   └── internal/
│       └── CloudAwareHealthCheck.java ← 覆盖 HealthCheck.Default
│
└── event/                        ← 云领域事件
    ├── ConfigChangedEvent.java   ← ConfigStore 变更通知
    ├── ObjectStoredEvent.java    ← ObjectStorage 写入后触发
    └── ObjectDeletedEvent.java   ← ObjectStorage 删除后触发
```

### 设计要点

#### 1. `internal/` 包隔离实现（遵循 freeway-db）

`internal/` 下的类为模块内部实现，**不作为公开 API 承诺兼容性**。应用代码应依赖接口（`ConfigStore`, `ObjectStorage`），不应直接引用 `internal/` 下的类。这与 `PoolDefault` -> `DatabaseImpl` 不暴露给用户的模式一致。

#### 2. IoC 接线收敛到子模块类（遵循 HttpModule/DbModule）

每个子系统有自己的 `*Module` 类——该类是唯一的 IoC 感知代码：

```java
// CloudConfigModule.java — 示例
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
                public void start(Container c) {
                    ConfigStore store = c.get(ConfigStore.class);
                    if (store instanceof AutoCloseable ac) {
                        // 由 RuntimeHook 管理不需要额外操作；
                        // 如果 store 需要连接初始化，在此处理
                    }
                }
                @Override
                public void stop(Container c) {
                    ConfigStore store = c.get(ConfigStore.class);
                    if (store instanceof AutoCloseable ac) {
                        try { ac.close(); } catch (Exception ex) {
                            LOG.warn("Failed to close ConfigStore", ex);
                        }
                    }
                }
            })
            .before("freeway.http.server");
    }
}
```

#### 3. CloudModule 聚合子模块（安装去重保证安全）

```java
// CloudModule.java
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

`binder.install()` 已内置去重（按 `module.getClass()`），可以同时传入 `CloudConfigModule` 和 `CloudModule` 不会重复。

#### 4. 同步 API 设计（遵循 virtual-thread-default 模式）

`ObjectStorage` 接口使用同步方法，与 `Database` 一致。框架默认使用 virtual threads 处理并发——调用方如需要异步编排，外层包裹 `Thread.startVirtualThread()` 而非接口层面返回 `Future`：

```java
// ObjectStorage.java — 同步接口
public interface ObjectStorage {
    Optional<byte[]> get(String bucket, String key) throws StorageException;
    PutResult put(String bucket, String key, byte[] data, ObjectMetadata metadata) throws StorageException;
    void delete(String bucket, String key) throws StorageException;
    List<ObjectEntry> list(String bucket, String prefix) throws StorageException;
    Optional<URL> presignedUrl(String bucket, String key, Duration ttl);
}
```

**理由**：`Database` 同步、`EventBus.publish()` 同步（async 变体单独提供）、`Pool.borrow()` 同步。框架偏好"接口同步 + virtual thread 并发"而非接口层返回异步类型。这是从第一版设计的一个关键修正。

#### 5. 独立构建器

每个主要抽象提供 `*Builder`，遵循 `DatabaseBuilder` / `WebServerBuilder` 模式：

```java
// ObjectStorage — 无 IoC 环境
var storage = ObjectStorageBuilder.localFs(Path.of("/data")).build();

// 使用
storage.put("my-bucket", "key.txt", data, new ObjectMetadata("text/plain", data.length, Map.of()));
```

#### 6. 领域事件

Cloud 操作产生领域事件，通过 EventBus 发布：

```java
// CloudStorageModule 中
binder.contribute(EventSubscriber.class)
    .add(EventSubscriber.of(ObjectStoredEvent.class, event -> {
        LOG.debug("Object stored: {}/{}", event.bucket(), event.key());
    }));
```

事件类型（record）：

```java
// ConfigChangedEvent.java
public record ConfigChangedEvent(String key, String oldValue, String newValue) {}

// ObjectStoredEvent.java
public record ObjectStoredEvent(String bucket, String key, long size, String etag) {}

// ObjectDeletedEvent.java
public record ObjectDeletedEvent(String bucket, String key) {}
```

Discovery 和 RPC 不产生领域事件——Discovery 是拉取操作，RPC 调用频率过高不应走 EventBus。

#### 7. ConfigStore watch 机制

```java
// ConfigStore.java
public interface ConfigStore {
    Optional<String> get(String key);
    Map<String, String> asMap();

    /** 订阅指定 key 的变化。实现可以忽略（返回 NOOP）。 */
    default ConfigSubscription watch(String key, Consumer<String> listener) {
        return ConfigSubscription.NOOP;
    }
}

// ConfigSubscription.java
@FunctionalInterface
public interface ConfigSubscription extends AutoCloseable {
    ConfigSubscription NOOP = () -> {};
    @Override void close();
}
```

`LocalConfigStore` 不实现 watch（NOOP）；`K8sConfigMapStore` 利用 K8s watch API 实现；`AwsSsmConfigStore` 可选择轮询或 EventBridge 通知。

#### 8. RPC —— 远程调用

##### 8.1 调用链路

```
Service A (caller, virtual thread)           Service B (callee, 普通 Freeway 应用)
──────────────────────────────               ──────────────────────────────

routeHandler.handle(ctx)
  │
  ├─ rpc.call("user-service", request)
  │     │
  │     ├─ ① discovery.getInstances("user-service")
  │     │     → List.of(new ServiceInstance("user-service", "10.0.1.5", 9090, false, ...))
  │     │
  │     ├─ ② 选实例（首可用 / 轮询 / 最少连接 — 策略可插拔）
  │     │
  │     ├─ ③ httpClient.send(                          ──── HTTP/1.1 ────→   WebServer
  │     │       GET http://10.0.1.5:9090/api/users/42,                           │
  │     │       headers: {Accept: application/json}                          routeHandler.handle(ctx)
  │     │     )                                                                  │
  │     │         │                                                          ctx.sendJson(200, user)
  │     │         │                                                              │
  │     │         │  ←─── HTTP/1.1 200 ───                                     │
  │     │         │       Content-Type: application/json
  │     │         │       {"id":42, "name":"Alice"}
  │     │
  │     ├─ ④ RpcResponse(200, headers, body)
  │     │
  │     ├─ ⑤ response.bodyAs(User.class, jsonCodec) → User(id=42, name="Alice")
  │     │
  │     └─ return user
  │
  └─ html.render(user)
     ctx.sendHtml(...)
```

**关键点**：
- Service B 不需要任何特殊代码——它就是普通的 Freeway HTTP 应用
- 调用是**同步阻塞**的——`rpc.call()` 在 virtual thread 中执行，阻塞无成本
- 实例选择策略可插拔（`RpcClient` 的默认实现用 `findFirst()`，可通过配置或扩展切换）
- 错误路径：实例列表为空 → `RpcException("No instance for service: user-service")`；HTTP 4xx/5xx → `RpcResponse.status` 非 2xx，调用方决定是否抛异常

##### 8.2 接口设计

`RpcClient` 是 ServiceDiscovery 的自然消费者——将服务名解析为实例地址，执行 HTTP 调用，处理序列化：

```java
// RpcClient.java — 同步接口
public interface RpcClient {
    /** 按服务名发起调用。serviceId 通过 ServiceDiscovery 解析为实际地址。 */
    RpcResponse call(String serviceId, RpcRequest request);
}

// RpcRequest.java — 不变 record
public record RpcRequest(
    String method,          // GET, POST, PUT, DELETE
    String path,            // /api/users/123
    Map<String, String> headers,
    byte[] body             // null for GET
) {
    public static RpcRequest get(String path) {
        return new RpcRequest("GET", path, Map.of(), null);
    }
    public static RpcRequest post(String path, byte[] body, String contentType) {
        var headers = Map.of("Content-Type", contentType);
        return new RpcRequest("POST", path, headers, body);
    }
}

// RpcResponse.java
public record RpcResponse(int status, Map<String, List<String>> headers, byte[] body) {
    public <T> T bodyAs(Class<T> type, JsonCodec codec) { ... }
    public boolean is2xx() { return status >= 200 && status < 300; }
}
```

**关键设计选择：不做 typed proxy**

不用 `@RpcClient` 注解 + 接口代理（如 OpenFeign），保持"显式声明"原则。调用的每一步对开发者可见：指定服务名、构造 request、拿到 response、解码 body。进阶封装（typed wrapper）由应用层自行构建，不在框架层引入。

**默认实现：`HttpRpcClient`**

```java
// internal/HttpRpcClient.java — 零额外依赖，基于 JDK HttpClient
final class HttpRpcClient implements RpcClient {
    private final ServiceDiscovery discovery;
    private final JsonCodec jsonCodec;
    private final HttpClient http;  // java.net.http.HttpClient

    @Override
    public RpcResponse call(String serviceId, RpcRequest request) {
        ServiceInstance instance = discovery.getInstances(serviceId).stream()
            .findFirst()
            .orElseThrow(() -> new RpcException("No instance for service: " + serviceId));
        // ... build and send http request ...
    }
}
```

**与 ServiceDiscovery 的关系**：`RpcClient` 消费 `ServiceDiscovery`，不持有。`CloudRpcModule` 期望 `ServiceDiscovery` 已绑定（由 `CloudDiscoveryModule` 或外部适配器提供）。

```java
// CloudRpcModule.java
b.bind(RpcClient.class)
    .to(container -> new HttpRpcClient(
        container.get(ServiceDiscovery.class),
        container.get(JsonCodec.class)))
    .id("http");
```

**gRPC 适配器**（Phase 3，freeway-ext）：

```java
// freeway-cloud-grpc/GrpcRpcClient.java — 覆盖 RpcClient
b.bind(RpcClient.class)
    .to(GrpcRpcClient.class)
    .id("grpc")
    .primary();

// 需要额外的 proto 解析能力，不作为核心抽象
```

##### 8.3 分布式追踪

**问题**：RpcClient 调用跨服务后，A 的日志和 B 的日志无法关联。

```
Service A 日志: "calling user-service ... RpcException: timeout"
Service B 日志: 看不到任何错误——没有 traceId 关联
```

**设计**：`Tracer` 接口 + `TraceContext` record。默认实现用 SLF4J MDC 传递 traceId，外接 OpenTelemetry 走 freeway-ext 适配器。

```java
// rpc/trace/TraceContext.java
public record TraceContext(
    String traceId,    // 全局唯一的调用链标识
    String spanId,     // 当前 span
    String parentSpanId
) {
    // W3C Trace Context header
    public static final String HEADER_TRACE_ID = "traceparent";
    public static final String HEADER_TRACE_STATE = "tracestate";

    /** 从 HTTP 请求头提取 */
    public static Optional<TraceContext> fromHeaders(Map<String, String> headers) { ... }

    /** 注入到 HTTP 请求头，传递给下游 */
    public Map<String, String> toHeaders() { ... }

    /** 生成新的根 TraceContext（无上游） */
    public static TraceContext root() { ... }

    /** 派生子 span */
    public TraceContext child() { ... }
}

// rpc/trace/Tracer.java
@FunctionalInterface
public interface Tracer {
    /** 创建一个 span，返回 closeable 以标记结束。 */
    Span start(String name, TraceContext parent);

    Tracer NOOP = (name, parent) -> Span.NOOP;

    interface Span extends AutoCloseable {
        Span NOOP = () -> {};

        void addTag(String key, String value);
        void addError(Throwable t);
        @Override void close();  // marks span as complete
    }
}
```

**默认实现**：`MdcTracer`——将 traceId 写入 SLF4J MDC，不依赖任何外部库。

```java
// rpc/internal/MdcTracer.java
final class MdcTracer implements Tracer {
    @Override
    public Span start(String name, TraceContext parent) {
        org.slf4j.MDC.put("traceId", parent.traceId());
        org.slf4j.MDC.put("spanId", parent.spanId());
        return new MdcSpan();
    }

    record MdcSpan() implements Span {
        @Override public void close() {
            MDC.remove("traceId");
            MDC.remove("spanId");
        }
        @Override public void addTag(String key, String value) { /* noop for MDC */ }
        @Override public void addError(Throwable t) { /* noop for MDC */ }
    }
}
```

**Freeway 已有 JUL MDC 实现**（`JULMDCAdapter`），所以 `MdcTracer` 可以直接写入 MDC 工作。

**`HttpRpcClient` 集成**：

```java
// HttpRpcClient 内部
@Override
public RpcResponse call(String serviceId, RpcRequest request) {
    // 1. 提取或创建 TraceContext
    TraceContext trace = TraceContext.fromThreadLocal()  // 从当前 MDC/Scope 读取
        .orElseGet(TraceContext::root);

    // 2. 注入到下游请求头
    Map<String, String> headers = new HashMap<>(request.headers());
    headers.putAll(trace.toHeaders());

    // 3. 创建 client span
    try (Span span = tracer.start("rpc:" + serviceId, trace)) {
        span.addTag("service", serviceId);
        span.addTag("method", request.method());
        span.addTag("path", request.path());

        RpcResponse resp = doCall(serviceId, new RpcRequest(request.method(), request.path(), headers, request.body()));

        if (!resp.is2xx()) {
            span.addTag("http.status", String.valueOf(resp.status()));
        }
        return resp;
    } catch (Exception e) {
        // span 在 close() 中记录异常
        throw e;
    }
}
```

**调用链效果**：

```
Service A                     Service B
traceId: abc123               收到请求头 traceparent: abc123/span-B-id
spanId: span-A-id             提取 TraceContext
MDC: {traceId: abc123}       MDC: {traceId: abc123, parentSpanId: span-A-id}
  │                              │
  ├─ rpc.call("svc-b", req)     ├─ 日志: "GET /api/users/42"
  │   注入 traceparent           │     [traceId=abc123, spanId=span-B-id]
  │   ──────────────────────→   │
  │                              │
  │   ←─── HTTP 500 ────────    ├─ 日志: "SQL error" [traceId=abc123]
  │                              │
  ├─ 日志: "RPC failed: 500"    │
  │   [traceId=abc123]          │
```

##### 8.4 韧性 / 容错

`RetryPolicy` 和 `CircuitBreaker` 让 RPC 在生产环境下可用。默认实现使用 JDK 内置并发原语（`synchronized` + `volatile`），零外部依赖。

```java
// rpc/policy/RetryPolicy.java
public interface RetryPolicy {

    /** 判断是否应该重试本次调用。@param attempt 从 0 开始计数 */
    boolean shouldRetry(int attempt, RpcException failure);

    /** 给出第 attempt 次重试前的等待毫秒数 */
    long backoffMillis(int attempt);

    // 内置策略 —— 最多重试 3 次，指数退避
    RetryPolicy DEFAULT = new ExponentialBackoff(3, 100, 5000);

    // 不重试
    RetryPolicy NO_RETRY = (attempt, failure) -> false;

    record ExponentialBackoff(int maxRetries, long baseMillis, long maxMillis) implements RetryPolicy {
        @Override
        public boolean shouldRetry(int attempt, RpcException failure) {
            return attempt < maxRetries && failure.isRetryable();  // 连接失败可重试，4xx 不重试
        }
        @Override
        public long backoffMillis(int attempt) {
            return Math.min(baseMillis * (1L << attempt), maxMillis);  // 100, 200, 400, ...
        }
    }
}

// rpc/policy/CircuitBreaker.java
public interface CircuitBreaker {

    enum State { CLOSED, OPEN, HALF_OPEN }

    State state();

    /** 调用前查询——是否允许放行。OPEN 时返回 false。 */
    boolean allowRequest();

    /** 调用成功——CLOSED 保持，HALF_OPEN 转 CLOSED */
    void onSuccess();

    /** 调用失败——CLOSED 累计失败数，达到阈值后转 OPEN */
    void onFailure();

    // 默认实现：半开窗口 30s，OPEN 保持 30s 后转 HALF_OPEN
    static CircuitBreaker create(int failureThreshold, Duration openWindow) { ... }

    // 不熔断
    CircuitBreaker NOOP = new CircuitBreaker() {
        @Override public State state() { return State.CLOSED; }
        @Override public boolean allowRequest() { return true; }
        @Override public void onSuccess() {}
        @Override public void onFailure() {}
    };
}
```

**`HttpRpcClient` 中的韧性编排**：

```java
// HttpRpcClient.java — call() 内部编排
@Override
public RpcResponse call(String serviceId, RpcRequest request) {
    TraceContext trace = resolveTrace();
    int attempt = 0;

    while (true) {
        try {
            // 熔断判断
            if (!breaker.allowRequest()) {
                throw new RpcException("Circuit breaker is OPEN for " + serviceId, false);
            }

            RpcResponse resp = doSingleCall(serviceId, injectTrace(request, trace));
            breaker.onSuccess();
            return resp;

        } catch (RpcException e) {
            breaker.onFailure();

            if (!retryPolicy.shouldRetry(attempt, e)) {
                throw e;  // 不重试——直接抛给调用方
            }

            sleep(retryPolicy.backoffMillis(attempt));
            attempt++;
        }
    }
}
```

**RpcException 区分可重试 vs 不可重试**：

```java
public class RpcException extends RuntimeException {
    private final boolean retryable;

    // 连接失败、超时 → retryable = true
    // HTTP 4xx（客户端错误）→ retryable = false
    // HTTP 5xx → retryable = true（但 CircuitBreaker 会累计失败）
}
```

**与 IoC 的接线**（`CloudRpcModule` 中）：

```java
// RetryPolicy 和 CircuitBreaker 可通过配置覆盖，也可通过 .primary() 被适配器覆盖
b.bind(RetryPolicy.class)
    .to(RetryPolicy.DEFAULT);  // 默认：指数退避 3 次

b.bind(CircuitBreaker.class)
    .to(container -> CircuitBreaker.create(
        5, Duration.ofSeconds(30)));  // 默认：5 次失败后熔断

b.bind(Tracer.class)
    .to(MdcTracer.class)
    .id("mdc");
```

**gRPC 适配器复用同一个 Tracer/RetryPolicy/CircuitBreaker**——这些是与传输无关的纯策略，gRPC 实现也可以消费它们。

#### 9. ServiceDeclaration —— "哪些服务可以注册"

##### 问题

第 10 节的自注册逻辑硬编码了"只有一个 HTTP 端点"的假设。但实际可能有：
- HTTP 端点（Freeway 内置）
- gRPC 端点（freeway-cloud-grpc 提供）
- 自定义协议端点（应用自己的模块）
- 多个 HTTP 端口（admin API + public API）

需要一个扩展机制让**任何模块**声明"我这里有服务要注册"，由 `CloudDiscoveryModule` 统一收集后调用 `registry.register()`。

##### 设计：扩展点模式

```java
// discovery/ServiceDeclaration.java — 公开类型
@FunctionalInterface
public interface ServiceDeclaration {
    /** 从 Container 构造此次启动需要注册的 ServiceInstance。
     *  在 HTTP server 启动后调用（host:port 已确定）。 */
    ServiceInstance resolve(Container container);
}
```

各模块**声明**自己要注册的服务，不直接调用 `registry.register()`：

```java
// ── HttpModule 中（或 CloudDiscoveryModule 中）──
// 声明 HTTP 端点
binder.contribute(ServiceDeclaration.class)
    .add("http", (ServiceDeclaration) container -> {
        WebServer server = container.get(WebServer.class);
        SymbolSource symbols = container.get(SymbolSource.class);
        String serviceId = symbols.resolve(
            CloudConfigKeys.REGISTRY_SERVICE_ID,
            symbols.resolve("freeway.app.name", "freeway-app"));
        return new ServiceInstance(
            serviceId, server.host(), server.port(), false, Map.of(), null);
    });

// ── 未来 GrpcModule ──
binder.contribute(ServiceDeclaration.class)
    .add("grpc", (ServiceDeclaration) container -> {
        GrpcServer server = container.get(GrpcServer.class);
        return new ServiceInstance(
            "grpc-endpoint", server.host(), server.port(), false, Map.of(), null);
    });

// ── 用户自定义模块 ──
binder.contribute(ServiceDeclaration.class)
    .add("admin", (ServiceDeclaration) container -> {
        return new ServiceInstance("admin-api", "0.0.0.0", 9091, true, Map.of(), null);
    });
```

`CloudDiscoveryModule` 的注册 Hook 统一收集并注册：

```java
// CloudDiscoveryModule.java — 注册 hook (after HTTP server)
binder.contribute(RuntimeHook.class)
    .add("freeway.cloud.registry", new RuntimeHook() {
        private final List<ServiceInstance> registered = new ArrayList<>();

        @Override
        public void start(Container c) {
            ServiceRegistry registry = c.get(ServiceRegistry.class);
            var declarations = c.extension(ServiceDeclaration.class).all();

            for (ServiceDeclaration decl : declarations) {
                ServiceInstance instance = decl.resolve(c);
                registry.register(instance);
                registered.add(instance);
                LOG.info("Registered {} at {}:{}",
                    instance.serviceId(), instance.host(), instance.port());
            }
        }

        @Override
        public void stop(Container c) {
            ServiceRegistry registry = c.get(ServiceRegistry.class);
            for (ServiceInstance instance : registered) {
                try {
                    registry.deregister(instance);
                } catch (Exception ex) {
                    LOG.warn("Failed to deregister {}", instance.serviceId(), ex);
                }
            }
            registered.clear();
        }
    })
    .after("freeway.http.server");
```

**设计选择**：`ServiceDeclaration` 是 `@FunctionalInterface`（而非带 `name()` 的接口）——因为命名已在 `contribute().add("id", ...)` 中提供。保持接口最小化。

##### 哪种服务不需要注册

静态第三方服务（PostgreSQL、Redis）地址由配置指定，不需要也不应该注册到 Discovery：

```properties
# 这些已由 DbConfigKeys 处理
freeway.db.url=jdbc:postgresql://db.internal:5432/mydb
freeway.db.username=app
freeway.db.password=${SECRET:db.password}
```

只有**动态分配地址的服务**才走 Discovery——即 Freeway 应用自身的端点。

#### 10. 服务从创建到消费的完整生命周期

```
阶段 1: Config/Secret/Storage 连接    (before http.server)
阶段 2: Discovery 客户端就绪           (before http.server)
阶段 3: HTTP Server 启动              (http.server — 现在 host:port 已知)
阶段 4: 自注册 Self-registration      (after http.server)
阶段 5: 运行时 — 发现 + 消费          (RpcClient 按需调用)
阶段 6: 自注销 → Server 停止 → 断开   (stop, reverse order)
```

##### 阶段 4 详细设计：自注册

`CloudDiscoveryModule` 注册两个 RuntimeHook——一个在 HTTP server 前（连接注册中心客户端），一个在 HTTP server 后（收集 `ServiceDeclaration` 并执行自注册）：

```java
// CloudDiscoveryModule.java
public void bind(Binder b) {
    // 绑定 ServiceDiscovery 和 ServiceRegistry（与之前相同）
    b.bind(ServiceDiscovery.class).to(...)
    b.bind(ServiceRegistry.class).to(...)

    // Hook 1: 连接注册中心（before HTTP server）
    b.contribute(RuntimeHook.class)
        .add("freeway.cloud.discovery", new RuntimeHook() {
            @Override
            public void start(Container c) {
                // 初始化 ServiceDiscovery / ServiceRegistry 客户端连接
                // 不执行注册——此时还不确定服务地址
            }
            @Override
            public void stop(Container c) {
                // 关闭客户端连接
            }
        })
        .before("freeway.http.server");

    // Hook 2: 收集 ServiceDeclaration，统一注册（after HTTP server）
    b.contribute(RuntimeHook.class)
        .add("freeway.cloud.registry", new RuntimeHook() {
            private final List<ServiceInstance> registered = new ArrayList<>();

            @Override
            public void start(Container c) {
                ServiceRegistry registry = c.get(ServiceRegistry.class);
                var declarations = c.extension(ServiceDeclaration.class).all();
                for (ServiceDeclaration decl : declarations) {
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
3. Maven artifactId（通过 manifest 读取 JAR 名称）→ 但 Freeway 不做 classpath 扫描，保持用配置

##### 阶段 5 详细设计：发现 + 消费

```java
// Service A 调用 Service B
class UserController {
    @Inject RpcClient rpc;

    public UserProfile getProfile(long userId) {
        // 1. RpcClient 内部: ServiceDiscovery.getInstances("user-service") → [instance:9090]
        // 2. RpcClient 内部: httpClient.send(GET, "http://host:9090/api/users/" + userId)
        // 3. 返回 RpcResponse → 解码为 UserProfile
        var resp = rpc.call("user-service",
            RpcRequest.get("/api/users/" + userId));
        return resp.bodyAs(UserProfile.class, jsonCodec);
    }
}
```

`HttpRpcClient` 的内部流程：
1. `discovery.getInstances(serviceId)` → 取第一个实例（未来可加负载均衡）
2. 构造 JDK `HttpRequest`：`http://{host}:{port}{path}`
3. 发送、读取、封装为 `RpcResponse`
4. 实例不可达时从 discovery 列表重试下一个

#### 11. 健康检查扩展

`CloudHealthContributor` 是扩展点接口——各适配器贡献云资源的状态检查，`CloudAwareHealthCheck` 聚合后覆盖 `HealthCheck.Default`：

```java
// CloudHealthContributor.java
public interface CloudHealthContributor {
    String name();          // e.g., "s3", "configmap"
    HealthResult check();   // never null
}

// HealthResult.java
public record HealthResult(boolean healthy, String detail) {
    public static HealthResult ok() { return new HealthResult(true, ""); }
    public static HealthResult unhealthy(String detail) { 
        return new HealthResult(false, Objects.requireNonNull(detail)); 
    }
}
```

`CloudAwareHealthCheck` 聚合所有 `CloudHealthContributor`，返回结构：

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

各适配器模块贡献自己的 `CloudHealthContributor`：

```java
// AwsCloudModule 中
b.contribute(CloudHealthContributor.class)
    .add("aws-s3", () -> {
        try {
            s3.listBuckets();
            return HealthResult.ok();
        } catch (Exception e) {
            return HealthResult.unhealthy(e.getMessage());
        }
    });
```

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
    public static final String STORAGE_TYPE   = PREFIX + ".storage.type";
    public static final String STORAGE_BUCKET = PREFIX + ".storage.bucket";
    public static final String STORAGE_BASE_PATH = PREFIX + ".storage.base-path";
    public static final String STORAGE_REGION = PREFIX + ".storage.region";
    public static final String STORAGE_ENDPOINT = PREFIX + ".storage.endpoint";  // MinIO/兼容 S3

    // ── Service Discovery ────────────────────────────────
    public static final String DISCOVERY_TYPE = PREFIX + ".discovery.type";
    public static final String REGISTRY_TYPE  = PREFIX + ".registry.type";
    public static final String REGISTRY_SERVICE_ID = PREFIX + ".registry.service-id";
    public static final String REGISTRY_SERVICE_HOST = PREFIX + ".registry.service-host";
    public static final String REGISTRY_SERVICE_PORT = PREFIX + ".registry.service-port";
    public static final String REGISTRY_HEALTH_PATH = PREFIX + ".registry.health-path";
    public static final String REGISTRY_META = PREFIX + ".registry.meta.";  // prefix for metadata

    // ── RPC ───────────────────────────────────────────────
    public static final String RPC_TYPE            = PREFIX + ".rpc.type";
    public static final String RPC_CONNECT_TIMEOUT = PREFIX + ".rpc.connect-timeout";
    public static final String RPC_REQUEST_TIMEOUT = PREFIX + ".rpc.request-timeout";

    // ── RPC / Retry ────────────────────────────────────────
    public static final String RPC_RETRY_MAX_ATTEMPTS  = PREFIX + ".rpc.retry.max-attempts";
    public static final String RPC_RETRY_BACKOFF_BASE   = PREFIX + ".rpc.retry.backoff-base";
    public static final String RPC_RETRY_BACKOFF_MAX    = PREFIX + ".rpc.retry.backoff-max";

    // ── RPC / Circuit Breaker ──────────────────────────────
    public static final String RPC_CB_ENABLED          = PREFIX + ".rpc.circuit-breaker.enabled";
    public static final String RPC_CB_FAILURE_THRESHOLD = PREFIX + ".rpc.circuit-breaker.failure-threshold";
    public static final String RPC_CB_OPEN_WINDOW       = PREFIX + ".rpc.circuit-breaker.open-window";

    // ── RPC / Trace ────────────────────────────────────────
    public static final String RPC_TRACE_ENABLED = PREFIX + ".rpc.trace.enabled";
    public static final String RPC_TRACE_TYPE    = PREFIX + ".rpc.trace.type";  // mdc, otel, ...

    // ── Health ───────────────────────────────────────────
    public static final String HEALTH_ENABLED = PREFIX + ".health.enabled";

    // ── Region (shared) ──────────────────────────────────
    public static final String REGION = PREFIX + ".region";
}
```

## 关键设计决策

| 决策 | 结论 | 依据 |
|------|------|------|
| ConfigStore vs SecretStore | **分离** | 安全审计类型区分、缓存/轮换策略正交、适配器来源解耦 |
| 同步 vs 异步 ObjectStorage | **同步** | 遵循 Database/Pool 模式，virtual thread 处理并发 |
| `internal/` 隔离 | **是** | 遵循 freeway-db，`PoolDefault`/`DatabaseImpl` 不公开 |
| 子模块粒度 | **每个抽象一个 Module** | 6 个子模块 + `CloudModule` 聚合 |
| RPC 层次 | **路径级调用，不做 typed proxy** | 显式 request/response，不引入注解代理 |
| 分布式追踪 | **Tracer 接口 + MDC 默认** | 跨服务关联日志；与已有 JUL MDC 兼容 |
| 韧性 | **RetryPolicy + CircuitBreaker** | 生产可用 RPC 必需的容错机制 |
| 领域事件 | **是** | 遵循 freeway-http `event/` 模式，EventBus 编排 |
| 独立构建器 | **每个抽象一个 Builder** | 遵循 `DatabaseBuilder`/`WebServerBuilder` |
| 健康检查 | **扩展点模式** | `CloudHealthContributor` 接口 + `contribute()` 收集 |

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
freeway.cloud.registry      ────┘  (从 WebServer 读取 bound address，register self)
freeway.db.migration        (在 server 之前，DB 启动后即可)

Stop (reverse order):
freeway.cloud.registry      ── deregister self (先摘除，停止接收流量)
freeway.http.server         ── stop server
freeway.cloud.*             ── close connections
```

关键约束：
- `freeway.cloud.registry` **必须在 `freeway.http.server` 之后**——服务地址只有 HTTP 服务器启动后才知道
- `freeway.cloud.registry` **必须在 `freeway.http.server` 之前停止**——先注销再关服务器
- `freeway.cloud.rpc` **不需要 RuntimeHook**——RpcClient 是按需调用的，不是启动时建立的连接
- `freeway.cloud.discovery` 的 hook 只管理 ServiceDiscovery/ServiceRegistry 客户端连接，不执行注册操作

## 文件清单（Phase 1）

Phase 1 新建文件：

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
│   └── internal/
│       └── LocalConfigStore.java
├── secret/
│   ├── SecretStore.java
│   ├── SecretSymbolSource.java
│   ├── CloudSecretModule.java
│   └── internal/
│       └── LocalSecretStore.java
├── storage/
│   ├── ObjectStorage.java
│   ├── StorageException.java
│   ├── ObjectMetadata.java
│   ├── ObjectEntry.java
│   ├── PutResult.java
│   ├── ObjectStorageBuilder.java
│   ├── CloudStorageModule.java
│   └── internal/
│       └── FileSystemStorage.java
├── discovery/
│   ├── ServiceDiscovery.java
│   ├── ServiceDeclaration.java  ← 扩展点
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
│   └── internal/
│       └── CloudAwareHealthCheck.java
└── event/
    ├── ConfigChangedEvent.java
    ├── ObjectStoredEvent.java
    └── ObjectDeletedEvent.java
```

Phase 1 修改文件：
- 父 pom.xml ← 添加 `<module>freeway-cloud</module>`
- CLAUDE.md ← 更新模块依赖图
- docs/CLOUD-DESIGN.md ← 标记已有实现对应关系

freeway-ext 适配器文件（Phase 3，先留骨架）：
```
freeway-cloud-aws/
  → AwsSsmConfigStore, AwsSecretsManagerStore, AwsS3Storage, AwsCloudModule

freeway-cloud-k8s/
  → K8sConfigMapStore, K8sSecretStore, K8sDnsDiscovery, K8sCloudModule

freeway-cloud-grpc/
  → GrpcRpcClient, GrpcCloudModule  (覆盖 RpcClient，引入 protobuf + gRPC 依赖)

freeway-cloud-otel/
  → OtelTracer  (覆盖 Tracer，引入 OpenTelemetry SDK，不依赖具体 exporter)
```

## 验证

```bash
# Phase 1 测试
mvn -pl freeway-cloud -am test

# 验证默认实现可用
mvn -pl freeway-cloud -am test -Dtest=LocalConfigStoreTest
mvn -pl freeway-cloud -am test -Dtest=LocalSecretStoreTest
mvn -pl freeway-cloud -am test -Dtest=FileSystemStorageTest
mvn -pl freeway-cloud -am test -Dtest=ConfigServiceDiscoveryTest

# 验证 IoC 接线
mvn -pl freeway-cloud -am test -Dtest=CloudModuleTest     # 注入 ConfigStore, SecretStore, etc.
mvn -pl freeway-cloud -am test -Dtest=HttpRpcClientTest   # RPC 客户端调用
mvn -pl freeway-cloud -am test -Dtest=MdcTracerTest      # MDC 追踪
mvn -pl freeway-cloud -am test -Dtest=RetryPolicyTest    # 重试策略
mvn -pl freeway-cloud -am test -Dtest=CircuitBreakerTest # 熔断器
mvn -pl freeway-cloud -am test -Dtest=CloudHealthModuleTest  # 健康检查端点扩展

# Phase 3 适配器编译验证
cd freeway-ext && mvn -pl freeway-cloud-aws compile
cd freeway-ext && mvn -pl freeway-cloud-k8s compile
```
