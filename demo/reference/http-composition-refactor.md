# HTTP / WebSocket 组合化重构方案

> **状态：已实施并通过审计修正（2026-11-12）。** 第 14 节记录已确定的
> 决策，第 15 节记录第一轮验收，第 16 节记录审计后的提交/framing/契约修正；
> 本文同时是这次重构的基线。
>
> 目标仓库：`freeway`（core）与 `freeway-ext`。基线：core
> `378b2d13`，工作区干净。
>
> 相关约定：`AGENTS.md` 的 “Extending versus composing”、
> `docs/architecture.md` 的 HTTP seam、`freeway-ext/AGENTS.md` 的
> “Core first, adapter second”。

## 1. 目标

消除框架类型之间的实现继承，让 Freeway 的 HTTP / WebSocket 扩展点只剩
**接口契约**：

- `HttpExchange`、`HttpRequest`、`HttpResponse`、`ExchangeMeta`、
  `RequestView`、`WebSocketSession` 继续是应用看到的接口，接口之间的
  `extends` 保留（契约组合，不是实现继承）。
- 新增 `HttpTransport` / `WebSocketTransport` 两个 SPI，引擎适配器只
  `implements` 它们。
- 共享策略集中到 final 的 `HttpExchangeSupport` /
  `WebSocketSessionSupport`，由引擎**组合**，而不是被引擎继承。
- 删除 `AbstractHttpContext`、`AbstractWebSocketSession` 以及所有
  `extends` 它们的类。
- `freeway-ext` 的 Undertow、Jetty HTTP/WS 适配器全部接入新 SPI，
  testkit 契约与 benchmark 同步迁移。

这不是一次“为了少几行代码”的重构。收益是结构性的：

1. `AGENTS.md` 里 “implementation shared with third-party adapters”
   这个 `extends` 例外不再需要存在；
2. “每请求复位”从一个必须遵守的约定，变成对象生命周期本身；
3. 三个引擎的状态码/头校验、`Date`、`Content-Length`、gzip 决策、
   body 上限从“三份恰好一致”变成“结构上同一份”。

## 2. 边界：哪些 `extends` 保留

| 类别 | 处理 | 例子 |
|---|---|---|
| 框架类型之间的 `class extends` | **全部删除** | `AbstractHttpContext`、`AbstractWebSocketSession` 及其子类 |
| 契约之间的 `interface extends` | **保留** | `HttpExchange extends ExchangeMeta, HttpRequest, HttpResponse` |
| JDK / 语言强制 | **保留** | `RuntimeException`、`InputStream`、`OutputStream`、`X509ExtendedKeyManager`、`AutoCloseable` |
| 包内封闭的协议层次 | **sealed interface + 组合** | `engine.http2.Http2Frame` 与各帧类型；不可见、不跨模块、无第三方实现者 |
| 回调 / 监听器接口 | **保留** | `WebSocketListener`、`Session.Listener` 等 |

验收命令（在 core 与 ext 根目录分别执行）：

```bash
# 1. 不再有框架类型被继承
rg "extends (AbstractHttpContext|AbstractWebSocketSession)" \
   freeway-core freeway-ext

# 2. 两个基类及其名字彻底消失
rg "AbstractHttpContext|AbstractWebSocketSession" \
   freeway-core freeway-ext

# 3. 适配器只实现 SPI
rg "class .* (implements|extends)" \
   freeway-ext/freeway-http-undertow/src/main/java \
   freeway-ext/freeway-http-jetty/src/main/java
```

第 1、2 条必须为空。第 3 条允许 JDK/第三方基类（例如 Jetty 的
`Session.Listener`、Undertow 的 `AbstractReceiveListener`），但**不允许
再出现任何 core 的框架类型**。

## 3. 现状审计

### 3.1 core 的继承点

`freeway-http/src/main/java` 中框架实现继承只有两处：

| 类型 | 行数 | 职责 |
|---|---:|---|
| `http/AbstractHttpContext.java` | 357 | 元数据、path vars、body 限流、状态码/头校验、JSON/类型转换、`Date`、Vary、send 简写、SSE 头 |
| `http/websocket/AbstractWebSocketSession.java` | 175 | 升级请求的元数据快照 + 帧操作空壳 |

其余 `extends` 都是 JDK（`InputStream`/`OutputStream`、
`RuntimeException`）或接口契约组合；HTTP/2 帧族已改为
`engine.http2.Http2Frame` sealed interface + 组合。

### 3.2 ext 的继承点

| 类型 | 继承 | 说明 |
|---|---|---|
| `UndertowHttpExchange` | `AbstractHttpContext` | 池化在 `ThreadLocal` 中，`reset()` 复位 |
| `JettyHttpExchange` | `AbstractHttpContext` | 同上 |
| `UndertowWebSocketSession` | `AbstractWebSocketSession` | 帧操作委托 Undertow |
| `JettyWebSocketSession` | `AbstractWebSocketSession` | 帧操作委托 Jetty |

benchmark 没有继承框架类型，但 `freeway-benchmark` 的三个类直接
`new HttpContextImpl(...)` 并调用包私有的 `reset(...)`；这是 core 内部
类型在 ext 的唯一穿透点，重构时必须一起迁移。

### 3.3 具体耦合点

- `HttpResponseWriter` / `Http1xResponseWriter` / `Http2ResponseWriter`
  的方法参数是具体类型 `HttpContextImpl`，并从它读取状态、头、method、
  keep-alive。
- `HttpSession.echoRequestId(HttpContextImpl)` 以具体类型为参数。
- `JettyHttpEngine` 调用包私有的 `ctx.responded()`。
- `Http1xSession` 连接级复用一个 `HttpContextImpl`，每请求
  `reset(...)`；`reset` 里承担 `resetExchangeMeta()`、
  `resetMaxBodySize()`、`responseHeaders.clear()`、
  `pathVariables.clear()`。
- `UndertowHttpEngine` / `JettyHttpEngine` 各自用 `ThreadLocal` 池化
  `HttpExchange`，`dispatch` 先 `reset` 再设置 compression。
- `UndertowHttpExchange.body()` 把 Undertow 的 `RequestTooBigException`
  翻译成 `BodyTooLargeException`；翻译点当前和 HTTP 逻辑混在一起。
- `WebSocketReadLoop` 的参数是具体的 `WebSocketSessionImpl`，读循环依赖
  `markOpen/markClosed/writeFrame/closeCode/closeReason`。
- `AbstractWebSocketSession.closeReason(String)` 的 123 字节截断只有
  adapter 使用；内建引擎的 `WebSocketSessionImpl.close` 对超长 reason
  直接抛异常。这是一个已存在的引擎分歧。

## 4. 目标结构

### 4.1 HTTP

```
                应用 / 框架组件（filter、route、staticfiles、error）
                              │ 只依赖
                              ▼
                        HttpExchange  (interface)
                              ▲ implements
                              │
                  HttpExchangeSupport  (final，唯一共享实现)
                  ├─ ExchangeMetaImpl          元数据
                  ├─ pathVariables             路由变量快照
                  ├─ bodyLimit + body cache    请求体限流/缓存
                  ├─ status + responseHeaders  响应状态
                  ├─ Date / Content-Length / Vary / gzip 决策
                  └─ JSON / coercion / send 简写
                              │ 只调用机制
                              ▼
                     HttpTransport  (public SPI)
                    ▲          ▲                ▲
                    │          │                │
        HttpTransportImpl   UndertowHttpTransport  JettyHttpTransport
        （内建引擎）        （freeway-ext）          （freeway-ext）
```

`HttpExchangeSupport` 是 final 的**策略对象**：它不提供任何覆写点，也不
被任何类型继承。引擎的 `HttpTransport` 实现只负责“读请求”和“把
`ResponseHead` + body 写到线上”。

### 4.2 WebSocket

```
WebSocketSession (interface)
      ▲ implements
WebSocketSessionSupport (final)
      │ 元数据 + close reason 截断 + 帧委托
      ▼
WebSocketTransport (public SPI)
      ▲
      ├─ WebSocketConnection      （内建引擎：帧/连接状态）
      ├─ UndertowWebSocketTransport
      └─ JettyWebSocketTransport
```

### 4.3 生命周期

- `HttpExchangeSupport`：**每个 HTTP 请求新建**，请求结束即丢弃。
- `WebSocketSessionSupport`：**每个升级连接新建**。
- `HttpTransport`：**每请求新建**，SPI 不暴露 `reset`：
  - 内建 HTTP/1：每请求一个（连接级的 socket / parser 仍在 session）；
  - 内建 HTTP/2：每 stream 一个；
  - Undertow / Jetty：每请求一个。
- 不再有 `resetExchangeMeta()`、`resetMaxBodySize()`、
  `HttpContextImpl.reset(...)`、adapter 的 `ThreadLocal` context 池。
- `ExchangeMetaImpl.reset()` 在 core 中失去唯一调用者，一并删除
  （`ExchangeMetaImpl` 是 engine-internal `XImpl`，无兼容承诺）。
- 决定的依据是第 7.3 节的 JMH 证据：每请求 transport + support
  比池化多约 64 ns/op，远小于连接、解析和 handler 的固定成本；
  换来的是复位义务和状态串扰失败模式的彻底消失。

## 5. 职责矩阵

| 职责 | 归属 | 说明 |
|---|---|---|
| `correlationId` / `startTime` / principal / attributes | Support | 组合 `ExchangeMetaImpl` |
| `pathVariables`、`setPathVars` 整体替换语义 | Support | 每请求新 map，不泄漏 |
| `maxBodySize` + 校验 + `bodyLimit()` | Support | 每请求初值即构造参数，无复位 |
| `LimitedInputStream` 包装、`body()` 缓存、`bodyText`、`bodyAsJson` | Support | 所有引擎跑同一个 limiter |
| 状态码校验（100..599） | Support | 内建引擎已有，适配器补齐 |
| header name/value 校验 | Support 调用 `internal.HttpHeaders` | RFC 7230 token + ISO-8859-1 |
| `committed` 守卫、重复 output 忽略 | Support | 统一语义 |
| `Date` 补齐（应用已设置则不覆盖） | Support | 三引擎行为一致 |
| `Content-Length` 计算/剔除 | Support | 框架权威；204/205/304 删除 |
| gzip **决策**（状态、min-size、Accept-Encoding、Content-Type） | Support | `ResponseFraming` 仍为单一规则源 |
| `Vary: Accept-Encoding` 合并 | Support | 只有实际压缩时添加 |
| `output(String/Json)`、`send/sendJson`、`addVary`、SSE 头 | Support | 原基类逻辑 |
| 请求读取：method/path/query/headers/body stream/TLS/remote | Transport | 引擎原生 |
| 响应落地：状态行/头/body/文件/SSE | Transport | 引擎原生 |
| HTTP/1.1 framing（CL / chunked / Connection） | Transport（writer） | 由 `ResponseHead` 推导 |
| HTTP/2 头过滤（connection 等）、DATA/END_STREAM/RST | Transport（writer） | 协议细节 |
| gzip **执行**（压缩字节/流） | Transport | 决策在 Support，执行在 Transport |
| 原生 body 上限翻译（Undertow `RequestTooBigException`） | Transport | 进入框架前统一成 `BodyTooLargeException` |

## 6. API 设计

### 6.1 `HttpTransport`

位置：`com.jujin.freeway.http.HttpTransport`（public SPI）。

```java
public interface HttpTransport extends RequestHead {

    // -- 请求机制 --
    // method/path/queryParams/headers 继承自 RequestHead。

    /** 传输层分帧后的原始请求体；每个请求至多调用一次。 */
    InputStream framedBodyStream() throws IOException;

    boolean isSecure();
    SSLSession sslSession();          // 可为 null
    String remoteAddress();

    // -- 响应机制 --
    /** 唯一的响应执行入口；head 已是最终 wire head，body 只执行一次。 */
    void writeResponse(ResponsePlan plan) throws IOException;

    /** 写 SSE 头并返回 emitter；head 不含 Content-Length。 */
    SseEmitter openSse(ResponseHead head) throws IOException;
}
```

契约要点：

- `writeResponse(ResponsePlan)` 是唯一响应执行入口；body 是 sealed 的
  `Bytes`/`Stream`/`File`/`Channel`/`Empty`，transport 不再有四套 body 方法。
- 编码只有一处：head 的 `Content-Encoding`；gzip 由 transport 执行，不再由
  support 和 transport 各做一次。
- HEAD/204/205/304 不读取 body source；transport 只写 head。支持层在长度已知
  且未压缩时给出 `Content-Length`，buffering adapter（Jetty/Undertow）可在 head
  缺失时为 HEAD 物化一次以保持与 GET 相同的长度，内置流式 transport 则保持
  无 CL 的 chunked 语义。
- `framedBodyStream()` 返回的是**未限流**的传输流；`HttpExchangeSupport` 负责
  套 `LimitedInputStream`。适配器自己的原生上限（Undertow
  `MAX_ENTITY_SIZE`）在 `framedBodyStream()` 返回的流里翻译成
  `BodyTooLargeException`。
- method/path/queryParams/headers 来自 `RequestHead`；`RequestViewSupport` 统一
  处理大小写不敏感的 header 查找与 path var 快照。
- `sslSession()` 允许 `null`，但 `HttpRequest` 的 TLS/body 方法不再提供默认值。

### 6.2 `ResponseHead`

位置：`com.jujin.freeway.http.ResponseHead`（public immutable value）。

```java
public record ResponseHead(int status, boolean headRequest, List<Header> headers) {

    public record Header(String name, String value) {
        public Header {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
        }
    }

    public ResponseHead {
        headers = List.copyOf(headers);
    }

    /** 204 / 205 / 304 不允许 body。 */
    public boolean allowsBody();

    /** 线上是否允许 body 字节：allowsBody && !headRequest。 */
    public boolean sendsBodyBytes();

    public boolean hasHeader(String name);       // 大小写不敏感
    public String header(String name);           // 首个值
    public List<String> headers(String name);
    public ResponseHead withHeader(String name, String value);
    public ResponseHead withoutHeader(String name);
}
```

设计决定：

- **`Content-Length` 只存在于 `headers` 里，不设第二个 `contentLength`
  字段。** 一个事实只有一个存放处，避免“头里一个值、字段里另一个值”。
- **head 只携带 `headRequest`，不携带完整 `method`。** writer 唯一需要
  的方法是 HEAD 判定；把 method 放进 head 会让 head 携带一个 writer
  永远不用、却可能与“是否抑制 body”不一致的事实。`headRequest` 就是
  那个最小事实，且不可能与 `sendsBodyBytes()` 的语义冲突。
- `ResponseHead` 是提交点的不可变快照；提交后 handler 再改 header
  不会影响已写出的响应。

### 6.3 `HttpExchangeSupport`

位置：`com.jujin.freeway.http.HttpExchangeSupport`（public final）。

```java
public final class HttpExchangeSupport implements HttpExchange {

    public HttpExchangeSupport(
            HttpTransport transport,
            JsonCodec jsonCodec,
            Coercer coercer,
            long maxBodySize,
            HttpServerConfig.CompressionConfig compression,
            String correlationId) { ... }

    /** 引擎/测试读取响应头（Connection、X-Request-Id 等）。 */
    public String responseHeader(String name);

    /** 当前请求生效的 body 上限。 */
    public long bodyLimit();
}
```

行为约定：

- `maxBodySize` 是构造参数，`setMaxBodySize` 只改当前请求的值；
  没有 base/reset 这一对概念。
- body 读取延迟创建
  `new LimitedInputStream(transport.framedBodyStream(), this::bodyLimit)`，
  因此 filter 在请求中途提高上限仍然立即生效。
- `setStatus` 校验 `100..599`；`setHeader` / `addHeader` 校验
  RFC 7230 token 与 ISO-8859-1；`committed == true` 后 header/status
  写入是 no-op，重复 `output` 忽略。
- `body(byte[])`：确保 `Date`；gzip 决策；设置
  `Content-Length`（压缩后长度）；bodyless 状态删除 CL；构造 head；
  调 `transport.writeResponse(head, body)`。
- `body(InputStream, long)`：已知长度且不压缩时设置 CL；
  gzip 或未知长度时删除 CL；调 transport 的流路径。
- `file(...)`：已知长度且不压缩时设置 CL；gzip 删除 CL；
  调 transport 的文件路径。
- `sse()`：设置 `Content-Type: text/event-stream`、
  `Cache-Control: no-cache`、`Date`，删除 CL，**不写
  `Connection`**；由 HTTP/1 transport 写 `Connection: close`。
- `pathVars()` 返回不可变副本；`setPathVars` 整体替换。
- `responseHeader(name)` 是 public：`Http1xSession`、
  `JettyHttpEngine`、testkit 需要读取，而 `HttpResponse` 接口不暴露
  “读响应头”。

### 6.4 WebSocket SPI

```java
public interface WebSocketTransport {

    boolean isOpen();
    void sendText(String text) throws IOException;
    void sendBinary(byte[] data) throws IOException;
    void ping(byte[] data) throws IOException;

    /** reason 已由 Support 截断到 123 字节以内。 */
    void close(int code, String reason) throws IOException;
    void flush() throws IOException;

    default void sendTextBatch(List<String> texts) throws IOException {
        for (String text : texts) sendText(text);
        flush();
    }
}
```

```java
public final class WebSocketSessionSupport implements WebSocketSession {

    public WebSocketSessionSupport(
        WebSocketTransport transport,
        String correlationId,
        String method,
        String path,
        Map<String, String> pathVariables,
        Map<String, List<String>> queryParams,
        Map<String, List<String>> headers) { ... }
}
```

- 元数据快照逻辑从 `AbstractWebSocketSession` 原样迁入。
- `close(int, String)` 在 Support 中做 123 字节 UTF-8 截断，再委托
  transport。内建引擎原来“超长直接抛异常”、adapter 原来“截断”的分歧
  就此统一为截断。
- `sendTextBatch` 在 Support 中委托 `transport.sendTextBatch`，保留内建
  引擎的单次 flush 优化；adapter 用默认实现即可。
- 内建引擎把 `WebSocketSessionImpl` 拆成：
  - `WebSocketConnection implements WebSocketTransport`：帧写、open/
    close 状态、closeCode/closeReason、read loop 内部方法；
  - `WebSocketSessionSupport`：交给 `WebSocketEndpoint.open(...)`。
- `WebSocketReadLoop` 改为接收 `WebSocketConnection`，不再接收
  `WebSocketSessionImpl`；`WebSocketUpgrade` 创建
  `connection + support`，把 support 交给 endpoint。

### 6.5 可见性与包位置

| 类型 | 位置 | 可见性 | 理由 |
|---|---|---|---|
| `HttpTransport` | `http` | public | ext adapter 实现 |
| `ResponseHead` | `http` | public | SPI 签名 |
| `HttpExchangeSupport` | `http` | public final | ext adapter 构造 |
| `WebSocketTransport` | `http.websocket` | public | ext adapter 实现 |
| `WebSocketSessionSupport` | `http.websocket` | public final | ext adapter 构造 |
| `HttpTransportImpl` | `http.engine` | public final | benchmark 跨 jar 使用；`XImpl` 命名 |
| `HeaderStore` | `http.internal` | public（方法 public） | Support 与 writer 共用 |
| `ResponseFraming` | `http.internal` | public | Support 与 writer 共用 |
| `Http1xResponseWriter` | `http.engine` | package-private | 内建引擎协议细节 |
| `Http2ResponseWriter` | `http.engine.http2` | public（现状） | HTTP/2 包外组装 |
| `WebSocketConnection` | `http.engine.ws` | public（现状） | `WebSocketUpgrade` 同包使用，沿用 |

`HeaderStore` 从 `engine` 移到 `internal` 的原因：`HttpExchangeSupport` 在
`com.jujin.freeway.http`，不能访问 `engine` 的包私有类型。`internal`
是仓库既定的“无稳定性承诺”位置，public 只是为了让兄弟包组装。

## 7. 生命周期成本评估

### 7.1 微基准

临时程序：`LifecycleCost.java`（不在仓库内），比较两种形态：

- **pooled**：复用 `SupportLike`，每请求执行 `meta.reset()`、
  `responseHeaders.clear()`、path vars 清空、`status/committed/body`
  字段复位、`maxBodySize` 复位；
- **alloc**：每请求 `new SupportLike(...)`，构造 `ExchangeMetaImpl`、
  `HeaderStore`、`Map.copyOf(pathVars)`；
- **built-in reset**：当前 `HttpContextImpl.reset(...)` 的完整成本，
  包含请求头拷贝、query 解析、`new RequestBody`。

JDK 25，`-Xms/-Xmx` 默认，7 轮取最小值，每轮 5,000,000 次；
用静态 `Object[]` sink 阻止逃逸分析消除分配。三次运行结果：

| 路径 | 第一次 | 第二次 | 第三次 |
|---|---:|---:|---:|
| pooled support-state reset | 42.8 ns/op | 40.8 ns/op | 41.4 ns/op |
| per-request support alloc | 51.3 ns/op | 50.1 ns/op | 50.2 ns/op |
| built-in full reset | 349.4 ns/op | 351.5 ns/op | 355.6 ns/op |
| **delta（alloc - reset）** | **8.4 ns/op** | **9.3 ns/op** | **8.8 ns/op** |

### 7.2 解读

- 每请求新建 support 相对“池化 + 复位”多约 **9 ns/op**，约占当前
  完整 context reset 成本（约 350 ns）的 **2.5%**。
- 这 9 ns 包含 `HttpExchangeSupport` 对象、`ExchangeMetaImpl`、
  `HeaderStore` 和 path-vars 快照的分配；`ExchangeMetaImpl` 里的
  `Instant.now()` 与 correlation id 生成在两种形态下都要付。
- transport 每请求新建是同一量级的另一个小对象；相比删除
  `ThreadLocal` 池、`reset` 和池化状态泄漏风险，这个成本值得付。

**JMH 复测**（`freeway-benchmark`，JDK 25，3 s × 5 fork，完整 exchange
路径，`-prof gc`）：

| 形态 | ns/op | B/op |
|---|---:|---:|
| pooled（复用 transport + support） | 503.703 | 5360 |
| per-request（每请求新建 transport + support） | 555.304 | 5544 |
| **delta** | **+51.6（约 +10%）** | **+184** |

无 `-prof gc` 的独立运行给出 498.560 ± 39.860 与
562.292 ± 45.928 ns/op，差值约 64 ns/op，与上表在误差范围内一致。
GC 时间每 3 s iteration 都是 21 ms，没有可测差异；多出的 184 B/op
是 support、`ExchangeMetaImpl`、`HeaderStore` 和快照对象的全部成本。

按 100k req/s 估算，64 ns/op ≈ 6.4 ms CPU/秒（单核不到 1%）；
按 1M req/s 估算 ≈ 64 ms/秒（单核约 6%）。这是最坏情况的上界，
因为 JMH 的单次 exchange 没有真实 handler、body 读取或下游调用。

### 7.3 决定

**采用每请求新建 support + 每请求 transport**。不保留任何
`reset()`：

- 不存在“忘记复位”的失败模式；
- `maxBodySize`、principal、attributes、path vars、响应头、状态码
  全部随对象丢弃；
- `ContextContract` 里“跨请求不串状态”的断言从“测试保证”变成
  “形状保证”，测试仍然保留，作为接口契约；
- 端到端多付约 64 ns/op（约 10%），其中 pure support-state 差约
  9 ns，其余是 transport/support 相关分配与更大的对象图；池化把这
  一切换回复位义务，交易不成立。

## 8. 行为变化（必须写进 CHANGELOG）

1. **响应策略收敛**：状态码范围校验、header 校验、`Date`、CL、gzip
   决策、Vary 现在只有 `HttpExchangeSupport` 一份。Undertow / Jetty
   会开始对非法状态码抛 `IllegalArgumentException`，并统一补 `Date`
   （需在 adapter 契约测试里确认容器不会重复添加）。
2. **`Content-Length` 框架权威**：`body(byte[])` 用实际字节数
   （gzip 后用压缩后长度）覆盖 handler 设置的值；`body(InputStream,
   len)` 用 `len`，`len < 0` 时删除；`file` 用 `length`；
   204/205/304 一律删除。三个引擎不再“有的覆盖、有的保留”。
3. **每请求 support / transport**：`reset` 不再存在；
   `HttpContextImpl.reset` 的 principal/attributes/limit/响应头复位
   义务消失。依赖 `reset` 的测试（`keepAliveReset*`）删除或改为
   “新建即空”。
4. **SSE 的 `Connection`**：共享层不再写 `Connection: keep-alive`。
   HTTP/1 writer 为 SSE 写 `Connection: close`；Undertow / Jetty 交给
   容器；HTTP/2 过滤 `Connection`。
5. **Undertow 原生上限翻译位置**：`RequestTooBigException` →
   `BodyTooLargeException` 移入 `UndertowHttpTransport.framedBodyStream()`，
   413 语义不变。
6. **gzip 文件路径**：决策在 Support，执行在 Transport；内建引擎保留
   sendfile（仅未压缩、body 允许、长度 ≥ 64 KiB），adapter 走缓冲。
7. **WebSocket close reason**：统一截断到 123 字节；内建引擎不再对
   超长 reason 抛 `IllegalArgumentException`。
8. **`HttpContextImpl` 不再是 handler 看到的类型**：handler 仍然只看到
   `HttpExchange`；内建引擎类型改名 `HttpTransportImpl`，ext/benchmark
   的编译错误就是迁移路径。

## 9. 逐文件改动清单

### 9.1 core 新增

| 文件 | 内容 |
|---|---|
| `freeway-http/src/main/java/com/jujin/freeway/http/HttpTransport.java` | 6.1 的 SPI |
| `freeway-http/src/main/java/com/jujin/freeway/http/ResponseHead.java` | 6.2 的不可变快照 |
| `freeway-http/src/main/java/com/jujin/freeway/http/HttpExchangeSupport.java` | 6.3 的唯一策略实现（约 350 行） |
| `freeway-http/src/main/java/com/jujin/freeway/http/websocket/WebSocketTransport.java` | 6.4 的帧 SPI |
| `freeway-http/src/main/java/com/jujin/freeway/http/websocket/WebSocketSessionSupport.java` | 6.4 的会话快照 + 委托 |
| `freeway-http/src/main/java/com/jujin/freeway/http/internal/HeaderStore.java` | 从 `engine` 迁入，方法改 public |
| `freeway-http/src/main/java/com/jujin/freeway/http/internal/ResponseFraming.java` | 从 `engine` 迁入 |

### 9.2 core 改写

| 文件 | 改动 |
|---|---|
| `engine/HttpContextImpl.java` → `engine/HttpTransportImpl.java` | `implements HttpTransport`；去掉 codec/coercer/limit/status/headers/responded/compression；只保留请求读取、`RequestBody`、`rawOut`、`keepAlive`、`writer`、`fileSender`、TLS/remote；新增 `drainRequestBody(long)`、`syncKeepAliveFromResponse(String)`、`isKeepAlive()` |
| `engine/HttpResponseWriter.java` | 参数改为 `(ResponseHead head, boolean keepAlive)` / `(ResponseHead head, byte[]...)`；删除对 `HttpContextImpl` 的引用 |
| `engine/Http1xResponseWriter.java` | 从 head 读状态/头；`writeHead` 一次写完状态行、所有头、framing（CL / chunked / Connection）；`writeBody` 只写 body；`end` 写 terminal chunk；`openSse` 复用 `writeHead(head, false)` + `ChunkedOutputStream` |
| `engine/http2/Http2ResponseWriter.java` | 从 head 读状态/头；保留 forbidden-header 过滤、`Set-Cookie` 多值、bodyless CL 剔除 |
| `engine/Http1xSession.java` | 每请求 `new HttpTransportImpl(...)`；`new HttpExchangeSupport(...)`；body drain 用 `transport.drainRequestBody(support.bodyLimit())`；`HttpSession.echoRequestId(support)` |
| `engine/Http2Session.java` | 每 stream `new HttpTransportImpl(...)` + `new HttpExchangeSupport(...)`；不再先构造再 `reset` |
| `engine/HttpSession.java` | `echoRequestId(HttpExchange)` |
| `engine/RequestBody.java` | 去掉 limit/cached 所有权；保留 chunked/fixed framing；`drain(long limit)`；`stream()` 返回未限流 framed 流 |
| `engine/ws/WebSocketSessionImpl.java` → `engine/ws/WebSocketConnection.java` | 只保留帧/连接状态；实现 `WebSocketTransport` |
| `engine/ws/WebSocketReadLoop.java` | 参数 `WebSocketSessionImpl` → `WebSocketConnection` |
| `engine/WebSocketUpgrade.java` | 创建 `WebSocketConnection` + `WebSocketSessionSupport`；endpoint 收到 support；read loop 收到 connection |
| `ExchangeMetaImpl.java` | 删除 `reset()`（唯一调用者消失） |
| `engine/package-info.java`、`HttpExchange.java`、`HttpEngine.java`、`internal/HttpHeaders.java` | 文档/注释同步；`setMaxBodySize` 的 reset 义务删除；header 校验方法移入 `HttpHeaders` |

### 9.3 core 删除

| 文件 | 原因 |
|---|---|
| `http/AbstractHttpContext.java` | 被 `HttpExchangeSupport` 取代 |
| `http/websocket/AbstractWebSocketSession.java` | 被 `WebSocketSessionSupport` 取代 |
| `engine/Headers.java` | 迁入 `internal` 并改名 `HeaderStore` |
| `engine/ResponseFraming.java` | 迁入 `internal` |

### 9.4 core 测试

| 文件 | 改动 |
|---|---|
| `StubHttpExchange.java` → `TestExchange.java` | 组合 `TestTransport` + 真实 `HttpExchangeSupport`；保留 `new TestExchange(...)`、`requestHeader/requestBody/queryParam` builder、`status()/responseBody()/responseHeader()` 访问器；内部不再 `extends` 任何框架类型 |
| `HttpExchangeTest.java` | `StubHttpExchange` → `TestExchange`；header 校验改为 `HttpHeaders.requireValidName/requireValidValue`；删除 `exchangeMetaResetClearsPerRequestState`（`reset` 删除），新增“新 support 初始状态为空” |
| `HttpContextImplTest.java` → `HttpExchangeSupportTest.java` + `Http1xTransportTest.java` | 策略测试用 `TestTransport` 记录 `ResponseHead/body`；wire 测试用 `ByteArrayOutputStream` 驱动 `Http1xResponseWriter`/`HttpTransportImpl`；`keepAliveReset*` 三项删除（生命周期变了） |
| `Http2ResponseWriterTest.java` | 直接构造 `ResponseHead`，不再构造 context |
| `BodyLimitParityTest.java` | 改用 `TestExchange`，注释改为“共享 support + 单一 `LimitedInputStream`” |
| 其余 9 个引用 `StubHttpExchange` 的测试 | `StubHttpExchange` → `TestExchange`（约 80 处机械替换）；如果 `TestExchange` 实现 `HttpExchange` 委托，调用形态基本不变 |
| `websocket/AbstractWebSocketSessionTest.java` → `WebSocketSessionSupportTest.java` | 快照不可变、close reason 截断、帧委托、`sendTextBatch` 委托 |
| 新增 `HttpExchangeSupportTest` | 状态码校验、头校验、Vary、415、body 限制与动态提高、204/304 CL、gzip 决策、HEAD、重复 output、Date |
| 新增 `Http1xTransportTest` | chunked、known-length、HEAD、204/304、gzip 流式、长度不匹配、sendfile、SSE、`Connection` |

`TestExchange` 的实现决定（方案 A）：

- `TestExchange implements HttpExchange`，所有 `HttpExchange/HttpRequest/
  HttpResponse` 方法都委托给内部的真实 `HttpExchangeSupport`，请求/响应
  机制由 `TestTransport` 提供。它仍然是**组合**（字段 + 委托），不是
  继承；约 80 处调用点保持 `ctx.status()` 形态，评审重点放在断言是否
  经过真实 support。
- `TestTransport` 记录提交的 `ResponseHead` 与 body，并提供可配置的
  请求快照（method/path/query/headers/body stream/TLS/remote）。
- 不再有第二个“假的 context”实现绕过共享策略；测试断言的必须是真实
  `HttpExchangeSupport` 的行为。

### 9.5 ext HTTP 适配器

| 文件 | 改动 |
|---|---|
| `UndertowHttpExchange.java` → `UndertowHttpTransport.java` | `implements HttpTransport`；只保留 Undertow 的 method/path/query/headers/body stream/TLS/remote、`writeResponse`、`writeResponseFile`、`openSse`；`RequestTooBigException` 翻译进 `framedBodyStream()`；删除 `reset`、`compression`、`responseStatus`、`responded`、`cachedBody`、`responseHeaders` |
| `UndertowHttpEngine.java` | dispatch 里 `new UndertowHttpTransport(exchange)` + `new HttpExchangeSupport(...)`；删除 `ThreadLocal` pool、`reset`、`setCompression`；`setHeader("X-Request-Id")` 走 support；handler 异常用 `ctx.isCommitted()` |
| `JettyHttpExchange.java` → `JettyHttpTransport.java` | 同上；`writeResponse` 用 request/response/callback；`openSse` 保留现有输出流 |
| `JettyHttpEngine.java` | 同上；`ctx.responded()` → `ctx.isCommitted()`；删除 pool/reset |
| `freeway-http-adapter-testkit/ContextContract.java` | 注释从 `AbstractHttpContext.readBody` 改为 `HttpExchangeSupport` + `LimitedInputStream`；断言不变 |

适配器的 `writeResponse(ResponseHead head, byte[] body)`：

```java
exchange.setStatusCode(head.status());
head.headers().forEach(h -> {
    if (!"Content-Length".equalsIgnoreCase(h.name())) {
        exchange.getResponseHeaders().add(h.name(), h.value());
    }
});
if (head.hasHeader("Content-Length")) {
    exchange.setResponseContentLength(Long.parseLong(head.header("Content-Length")));
} else {
    exchange.getResponseHeaders().remove(Headers.CONTENT_LENGTH);
}
if (head.sendsBodyBytes() && body.length > 0) {
    exchange.getResponseSender().send(ByteBuffer.wrap(body));
} else {
    exchange.endExchange();
}
```

（Jetty 版对应 `response.setStatus` / `response.getHeaders().add` /
`response.write(true, ByteBuffer.wrap(body), callback)`；无 body 时
`callback.succeeded()`。`Content-Length` 同样从 header 循环中排除，
再单独写入，避免重复。）

### 9.6 ext WebSocket 适配器

| 文件 | 改动 |
|---|---|
| `UndertowWebSocketSession.java` → `UndertowWebSocketTransport.java` | `implements WebSocketTransport`；保留 channel、maxMessageSize、sendLock、localCloseRequested、listener；`open(WebSocketListener, WebSocketSession)` 启动 receive listener 并 `onOpen(session)` |
| `UndertowHttpEngine.handleWebSocket` | 创建 transport + `WebSocketSessionSupport`；endpoint 收到 support |
| `JettyWebSocketSession.java` → `JettyWebSocketTransport.java` | `implements WebSocketTransport`；保留 session、sendLock、localCloseRequested；`open(WebSocketListener, WebSocketSession)` |
| `JettyWebSocketBridge.java` | 持有 support + transport；`onWebSocketOpen` 创建两者；close/错误走 support；`localCloseRequested` 走 transport |
| `WebSocketUpgradeContract` | 断言不变；新增一条 close reason 截断用例（若现有契约未覆盖） |

### 9.7 ext testkit / benchmark / 文档

| 文件 | 改动 |
|---|---|
| `freeway-benchmark/.../FilterChainBenchmark.java` | `HttpContextImpl` → `HttpTransportImpl` + `HttpExchangeSupport`；每个 op 构造（或 fixture 的 `newExchange()`），不再调用包私有 `reset` |
| `freeway-benchmark/.../HttpExchangeLookupBenchmark.java` | 同上；setup 构造一次，`@Benchmark` 只读查询（构造放 setup 不影响查找成本） |
| `freeway-benchmark/.../HttpExchangeOutputBenchmark.java` | 同上；输出路径每次构造新 support，避免 `committed` 状态复用 |
| `freeway-core/AGENTS.md` | 删除 “implementation shared with third-party adapters” 例外；适配器回归条目改为 `HttpExchangeSupport` + `LimitedInputStream` |
| `freeway-core/docs/architecture.md` | HTTP seam 图：`HttpExchangeSupport` + `HttpTransport`；说明 adapter 接入方式 |
| `freeway-core/docs/developer-guide.md` | Engine 列表：`HttpContextImpl` → `HttpTransportImpl` + `HttpExchangeSupport`；`WebSocketSessionImpl` → `WebSocketConnection` + `WebSocketSessionSupport` |
| `freeway-core/skills/freeway-dev/references/http.md` | 自定义引擎接入说明（SPI 方法、Support 构造、不要再继承） |
| `freeway-core/CHANGELOG.md` | 破坏性改动表：两个基类删除、SPI 名称、reset 义务消失、行为变化 1–8 |
| `freeway-ext/CHANGELOG.md` | 两个 adapter 的迁移、benchmark 同步 |

## 10. 测试计划

### 10.1 core

```bash
mvn -pl freeway-http -am test
mvn test
```

新增/迁移的重点：

- `HttpExchangeSupportTest`：纯策略，`TestTransport` 记录调用；断言
  `ResponseHead` 的 status/headers、CL 规则、gzip 决策、Date、415、
  413、Vary、HEAD、重复 output。
- `Http1xTransportTest`：真实字节流；chunked/CL/Connection、HEAD、
  204/304、流式 gzip、长度不匹配、sendfile、SSE。
- `Http2ResponseWriterTest`：直接 `ResponseHead`；forbidden headers、
  `Set-Cookie` 多值、bodyless CL。
- `BodyLimitParityTest`：`TestExchange` 的 body 路径与
  `LimitedInputStream` 仍然逐字节一致；动态提高上限仍然生效。
- `WebSocketSessionSupportTest`：快照不可变、close reason 截断、
  `sendTextBatch` 委托。

### 10.2 ext

```bash
cd ../freeway && mvn -DskipTests install
cd ../freeway-ext
mvn -o clean test
mvn -o spotless:check
```

必须全绿：

- `ContextContract`（TLS、跨请求不串状态、413、204 CL）
- `CompressionContract`
- `HeaderBudgetContract`
- `RemoteRpcContract`
- `WebSocketUpgradeContract`
- `freeway-benchmark` 至少 `mvn -o -pl freeway-benchmark -am test-compile`

### 10.3 反向验证（按仓库惯例）

- 去掉 `HttpExchangeSupport` 的 `LimitedInputStream` → 413 / parity
  测试必须红。
- 去掉 bodyless 的 CL 剔除 → 204/304 测试必须红。
- 去掉 `Http2ResponseWriter` 的 forbidden-header 过滤 →
  `Http2ResponseWriterTest` 必须红。
- 去掉 `WebSocketSessionSupport.close` 的截断 → close reason 测试
  必须红。
- 把 `Http1xResponseWriter` 的 chunked 推导改错 → streaming 测试必须红。

### 10.4 构建顺序（避免旧 `target/` 掩盖 ABI）

core 改完后 `mvn -DskipTests install`，再到 ext 做
`mvn -o clean test`。ext 的增量编译在 core ABI 变化时可能复用旧
class，`clean` 是唯一证明。

## 11. 实施顺序与提交

### Core（3 个提交）

1. **加骨架，不切引擎**
   - 新增 `HttpTransport`、`ResponseHead`、`HttpExchangeSupport`、
     `WebSocketTransport`、`WebSocketSessionSupport`；
   - `HeaderStore` / `ResponseFraming` 迁入 `internal`；
   - 新增 `HttpExchangeSupportTest` 等单元测试；
   - `mvn -pl freeway-http -am test` 绿。
2. **切换内建引擎**
   - `HttpContextImpl` → `HttpTransportImpl`；
   - writers / sessions / WebSocket 改造；
   - 删除两个基类与 `ExchangeMetaImpl.reset`；
   - 迁移 `StubHttpExchange` 与测试；
   - `mvn test` 绿。
3. **文档与 CHANGELOG**
   - AGENTS / ARCHITECTURE / DEVELOPER-GUIDE / skill / CHANGELOG；
   - `mvn test` 绿。

### Ext（3 个提交）

4. **HTTP 适配器**
   - Undertow/Jetty transport + engine dispatch；
   - testkit 注释；
   - `mvn -o clean test` + `spotless:check` 绿。
5. **WebSocket 适配器**
   - 两个 WS transport + bridge/engine 接线；
   - `mvn -o clean test` 绿。
6. **benchmark + ext CHANGELOG**
   - 三个 benchmark 文件；
   - `mvn -o -pl freeway-benchmark -am test-compile` 绿。

每个提交都要求对应构建绿；core 先 `install`，ext 必须 `clean`。

## 12. 风险与对策

| 风险 | 位置 | 对策 |
|---|---|---|
| 提交语义变化（status/headers/committed 所有权） | Support + 三个 transport | 已关闭：`HttpTransport.isCommitted()` 是唯一事实；提交前失败回滚，提交后失败按传输失败处理 |
| keep-alive 与 body drain | `RequestBody` / `Http1xSession` | `drain(limit)` 保持“超限不重用”；`BodyLimitParityTest` |
| Jetty callback 完成判断 | `JettyHttpTransport` / `JettyHttpEngine` | `isCommitted()` 替代包私有 `responded()`；无 body 时 `callback.succeeded()` |
| Undertow 异步 sender 与 SSE | `UndertowHttpTransport` | 保留现有 `SseOutputStream` 与回调，只改状态/头来源 |
| Date 重复 | adapter 容器 | 已关闭：Jetty 关闭自动 Date；`ContextContract` 断言只有一个 `Date` |
| HEAD 的 CL | 三个 writer | 已关闭：已知长度保留 CL；未知长度由缓冲 transport 物化后补实际 CL，流式 transport 用 chunked/无 CL；adapters 不补 0 |
| gzip 流式长度 | 内建 writer / adapter 默认 | gzip 时 head 无 CL；HTTP/1 chunked，HTTP/2 无 CL；默认缓冲路径补压缩后 CL |
| WebSocket 读循环与 session 解耦 | `WebSocketConnection` / `WebSocketReadLoop` | 先改内建 WS，再改 adapter；`WebSocketUpgradeContract` |
| close reason 行为变化 | `WebSocketSessionSupport` | 统一截断；新增契约用例 |
| benchmark 不再有包私有 `reset` | ext benchmark | 每 op 构造新 support；benchmark 本身就是每请求生命周期的真实成本 |
| `TestExchange` 委托方法多 | core tests | 只改测试代码；用生成/机械委托，评审重点看断言是否经过真实 support |

## 13. 工作量估算

| 部分 | 文件数 | 预计改动 |
|---|---:|---:|
| core 新增/改写 | ~20 | 1200–1500 行 |
| core 测试迁移 | ~15 | 400–600 行 |
| ext HTTP adapter | 4 | 500–650 行 |
| ext WS adapter | 4 | 250–350 行 |
| benchmark | 3 | 150–250 行 |
| 文档 / CHANGELOG | 7 | 200–300 行 |

净代码量大致持平或略降；**adapter 类会明显缩小**，因为
gzip/状态/头/body 限制/JSON 全部上移到 Support。真正的收益不是行数：

- `extends` 例外从 AGENTS 中消失；
- “每请求复位”变成不存在的失败模式；
- 三个引擎的 413 / gzip / Date / CL / header 校验从“三份恰好一致”
  变成“结构上同一份”；
- 第三方引擎只需要实现一个窄接口，不再需要理解或继承 core 基类。

## 14. 已确定的决策

1. **SPI 命名**：采用 `HttpTransport` / `WebSocketTransport` /
   `HttpExchangeSupport` / `WebSocketSessionSupport`。内建 HTTP context
   改名 `HttpTransportImpl`（`XImpl`：engine-internal，无替换点）。
2. **`ResponseHead` 形状**：`record ResponseHead(int status,
   boolean headRequest, List<Header> headers)`；`Content-Length` 只在
   `headers` 中；`allowsBody()` / `sendsBodyBytes()` 由状态和
   `headRequest` 推导。不携带完整 `method`。
3. **transport 生命周期**：每请求 transport + 每请求 support。依据是
   第 7 节的 JMH 数据：端到端多约 64 ns/op（约 10%），换来删除
   `reset`/`ThreadLocal` 池和状态串扰失败模式。
4. **`TestExchange` 形态**：方案 A——实现 `HttpExchange` 并委托真实
   `HttpExchangeSupport`，内部组合 `TestTransport`。
5. **实施粒度**：core 与 ext 在同一轮完成，core 先 `install`，ext
   必须 `clean` 构建；不留“适配器稍后迁移”的尾巴。

## 15. 第一轮实施与验收记录（2026-11-12）

按第 11 节的顺序完成，六个部分（core 骨架/切换引擎/文档、ext
HTTP/WS/benchmark）全部落地；第一轮提交为 core 3 个 + ext 2 个。

**构建与测试**

| 步骤 | 结果 |
|---|---|
| core `mvn -pl freeway-http -am test` | 504 绿 |
| core `mvn test`（全模块） | 418 / 320 / 125 / 504 / 531 / 128 / 359 全绿 |
| core `mvn -DskipTests -Dgpg.skip=true install` | 成功（本机无 gpg，跳过签名） |
| ext `mvn clean test`（clean 是唯一 ABI 证明） | 212 绿（Kafka 5 + Consul 2 项按环境变量跳过） |
| ext `mvn spotless:check` | 通过 |

跑过的契约：`ContextContract`、`CompressionContract`、
`HeaderBudgetContract`、`RemoteRpcContract`、`WebSocketUpgradeContract`
在 Undertow 与 Jetty 上各自继承并通过，另有 TLS/HTTP2/dispatch-io/
transport-limits 探针全绿。

**反向验证（第 10.3 节，逐项破坏→红→还原）**

| 破坏 | 结果 |
|---|---|
| `HttpExchangeSupport` 去掉 `LimitedInputStream` 包装 | `BodyLimitParityTest` 3 红 + `HttpExchangeSupportBodyLimitTest` 5 红 |
| `body(byte[])` 对 bodyless 状态不再删 `Content-Length` | `HttpExchangeSupportTest` 2 红 |
| `Http2ResponseWriter` 去掉 forbidden-header 过滤 | `Http2ResponseWriterTest` 2 红 |
| `WebSocketSessionSupport.close` 不截断 reason | `WebSocketSessionSupportTest` 1 红 |
| `Http1xResponseWriter` 的 chunked 推导写死为 false | `Http1xTransportTest` 3 红 |

还原后 core/ext 全量测试再次全绿，代码中无残留的破坏标记。

**实施中的三处方案修正**（方案本身没预见、由测试逼出来的）：

1. **`Content-Length` 框架权威与静态文件 HEAD 的冲突**：
   `body(byte[])` 现在用实际提交字节数覆盖 handler 设置的值，而
   `StaticResourceMount` 的 HEAD 分支曾“设 `Content-Length` 头 +
   `output(new byte[0])`”——会被覆盖成 0。改为把长度交给
   `output(nullInputStream(), length)`（未知长度传 -1），并让
   **transport 在 `sendsBodyBytes()==false` 时不读响应源**（SPI 默认
   实现、内建 transport、`TestTransport` 三处一致）：HEAD 不再打开文件，
   60 MB 文件的 HEAD 只读元数据。
2. **drain 的限额必须扣掉已读字节**：support 的 limiter 与
   `RequestBody.drain(limit)` 是两个读取点，drain 用“整段限额”会允许
   handler 读到上限后再多读。`RequestBody` 现在用计数流记录已消费字节，
   drain 的额度是 `limit - consumed`。
3. **handler 异常的落点**：Undertow/Jetty 原来用容器的 wire 状态
   （`isResponseStarted`/`isCommitted`）判断能否写 500；新形状下
   “已提交但还没起线上字节”（如 204）会被误判。改为先看
   `ctx.isCommitted()`：已提交就不再碰响应，未提交才写 500。

**第一轮记录的两个未做项**：

- `WebSocketUpgradeContract` 第一轮只覆盖握手（101），没有 close reason 用例。
  截断现在只存在于 `WebSocketSessionSupport.close` 一处，两个适配器的
  transport 收到的已经是截断后的值（不再各自实现、也不再有
  “内建抛异常 / 适配器截断”的分歧），由
  `WebSocketSessionSupportTest` 的 4 个用例钉住。**审计修正已补上端到端
  用例**：`handlerFailureClosesWithATruncatedReason` 由两个适配器共同继承。
- 第一轮实施已提交为 core 3 个 + ext 2 个提交；本轮审计修正尚未提交，
  工作区保持可审状态。

## 16. 审计修正（2026-11-12）

第一轮验收后的逐项审计发现测试绿并未覆盖 seam 的边界。本轮修正如下：

### 16.1 提交事实归 transport

`HttpTransport.isCommitted()` 是唯一提交事实：transport 在写出第一个
head 字节前翻转它，`HttpExchangeSupport.isCommitted()` 只读取这个事实，
不再自己先置 `committed`。提交前失败的输出会丢弃已准备的 status/headers
并重抛，`HttpServer` 因此能写 500；提交后的失败按传输失败处理。
缺文件/文件打开失败、提交前的流读取失败不再断连或挂起。回归：
`HttpExchangeSupportTest.fileOpenFailureLeavesTheExchangeRetryable`、
`ContextContract.missingFileBecomesAServerErrorInsteadOfAnEmptyResponse`。

### 16.2 framing 归 transport

默认（缓冲）transport 对物化后的 body 补上实际 `Content-Length`，包括
HEAD；流式 transport 保留无 CL 的 chunked 语义。HTTP/1.1 适配器只在响应
仍无 CL（如 SSE）时补 `Transfer-Encoding: chunked`，HTTP/2 不补，并过滤
`Connection`/Keep-Alive/Upgrade/TE 等连接级字段。support 统一过滤 handler
设置的 `Transfer-Encoding`。这样 HEAD 与 GET 的未知长度响应头一致，也消除了
容器补 `Content-Length: 0` 的缺陷。回归：
`ContextContract.unknownLengthBodiesFrameHeadAndGetTheSameWay`、
`handlerCannotInjectTransferEncoding`、
`CompressionContract.compressesLargeStreamingResponse`。

### 16.3 适配器契约

- Jetty 关闭自动 Date（`HttpConfiguration.setSendDateHeader(false)`），
  `ContextContract.responsesCarryExactlyOneDateHeader` 覆盖。
- Undertow/Jetty 的内部 WebSocket 错误关闭改走
  `WebSocketSessionSupport.close`，不再绕过 123 字节截断；
  `handlerFailureClosesWithATruncatedReason` 覆盖。
- `HttpExchangeSupport.setStatus` 在提交后是 no-op，与 header 一致。

### 16.4 命名与清理

- 响应生命周期统一用 **committed**：`HttpTransport.isCommitted()`、
  `HttpResponse.isCommitted()`、`HttpExchangeSupport.isCommitted()`，回滚辅助方法为
  `resetIfUncommitted()`；`HttpResponse.isCommitted()` 是抽象方法，不再用默认 false
  兜底。`HttpExchangeSupport` 的私有 `head()` 改为 `responseHead()`，避免与 HTTP HEAD
  混淆。迁移表中旧的 `responded()`/`responded` 字段只是历史代码名，不代表当前 API。
- HTTP/2 stream 的 `headersSent`/`isResponseCommitted()` 统一为
  `committed`/`isCommitted()`；H2 transport 的 `isCommitted()` 直接读取该 stream
  事实，不再持有第二份提交判断。
- 所有响应修改方法保持链式返回：`addVary` 也返回 `HttpResponse`。
- `HttpTransport.bodyStream()` 改为 `framedBodyStream()`，与 handler 侧
  `HttpRequest.bodyStream()`（已限流/缓存）区分；transport 返回的是未限流的
  protocol-framed 流。
- `HttpResponse` 不再提供会静默破坏长度/gzip 语义的 body 默认实现：
  `body(InputStream, long)` 与两个 `file` 都是抽象契约，由
  `HttpExchangeSupport`（或自定义实现）负责。`allowsResponseBody()` 统一为
  `allowsBody()`；204/205/304 的规则集中到 `HttpStatus.allowsBody(int)`，
  `HttpResponse` 和 `ResponseHead` 都委托它。
- `http.internal.Headers` → `HeaderStore`，与 `HttpHeaders` 的语法助手
  区分。
- 删除 `HttpResponse.suppressBodyBytes` / `ResponseFraming.suppressBodyBytes`
  的重复规则（生产已使用 `ResponseHead.sendsBodyBytes`），以及无调用者的
  `HeaderStore.setValueIfPresent/getAll`、`ExchangeMetaImpl.setCorrelationId`。
- `WebSocketReadLoop.readLoop` 去掉未使用的 `OutputStream` 参数。
- 同步 `HttpExchange`、`FreewayHttpEngineTest`、`BodyLimitParityTest` 中
  仍描述 “reset/reuse” 的旧生命周期文字。
- `HttpResponseWriter` 的实际形状是 writer 构造时持有 keepAlive，方法只接收
  `ResponseHead`（不是第 9.2 节写的 `(head, keepAlive)` 参数）；每响应一个
  writer，keepAlive 是 writer 的生命周期事实。

### 16.5 修正后验证

| 步骤 | 结果 |
|---|---|
| core `mvn test`（全模块） | 418 / 327 / 125 / 521 / 538 / 128 / 361 全绿 |
| core `mvn -pl freeway-http -am test` | 521 绿 |
| ext `mvn -o clean test` | 228 绿（51 / 47 / 51 / 17 / 21 / 41；7 项跳过） |
| ext `mvn -o spotless:check` | 通过 |

新增的契约用例：`ContextContract` 的 Date、未知长度 HEAD、handler TE、缺文件 500；
`CompressionContract` 的流式 gzip（缓冲 transport 的 CL 与压缩长度一致）；`WebSocketUpgradeContract` 的错误 close reason；
两个适配器的 HTTP/2 契约（单个 Date、hop-by-hop 字段过滤、未知长度 HEAD/GET 的 CL 一致）；
`ContextContract` 的文件响应 GET/HEAD framing；core 的 `HttpExchangeSupportTest` 回滚/状态 guard/TE、
`Http1xTransportTest` 长度不匹配/
205/HEAD、`HttpTransportImplTest` committed 标志、`HttpServerStandaloneTest` 缺文件 500。

## 17. HTTP/2 帧族的组合化（2026-11-13）

`BaseFrame` 抽象类已删除，改为包内 sealed `Http2Frame` 接口：

- 每个帧实现 `Http2Frame`，并**组合**一个 `FrameHeader` 字段；
- `Http2Frame` 提供 `header()`、`writeTo()` 和默认 `encode()`；
- `FrameSerializer`、`Http2Connection`、`Http2Stream` 都返回/接收
  `Http2Frame`；
- 分派改为 sealed pattern switch，生产路径不再有
  `(SettingsFrame) frame` 这类强制转换；
- 所有帧类型在 `permits` 中显式列出，新增帧类型时编译器会强制补分派。

这是设计品味/类型安全的改动，**不是性能优化**。pattern switch 仍会做类型判别，
sealed 只提供编译期穷尽性；接口 `header()` 的多实现还可能让调用点从单态变成
megamorphic。性能验证应单独做 JMH/端到端基准；真正可优化的是 `encode()` 的
`ByteArrayOutputStream`、入站 payload 复制和 HPACK/IO，而不是去掉 cast。
