# TASTE — Freeway 的设计品味

> 这不是 API 文档，也不是变更日志。它记录我们做设计判断时真正使用的审美标准：
> 什么样的结构值得保留，什么样的“方便”应该删除，什么样的破坏是值得的。
>
> 当规则和直觉冲突时，先回到一句话：**一个事实只能有一个所有者，读者不应该靠猜。**

---

## 1. 事实只有一个所有者

我们不缺抽象，缺的是把同一个事实只放一次。

### 响应：ResponsePlan

一条响应有四类事实：状态和头、body 从哪来、body 怎么编码、wire 上占多少字节。

过去它们分散在 HttpExchangeSupport、HttpTransport 的 default 方法、内置 transport 的 override 三处。
现在 support 只 plan，transport 只 execute：

    interface HttpTransport {
        void writeResponse(ResponsePlan plan) throws IOException;
    }

    record ResponsePlan(ResponseHead head, ResponseBody body) {
        boolean gzip(); // 只从 head 的 Content-Encoding 读
    }

    sealed interface ResponseBody
        permits Empty, Bytes, Stream, File, Channel {}

- support 选择 body、决定 encoding、写已知长度。
- transport 执行 gzip、framing、HEAD、file/channel，并关闭资源。
- gzip 只有一个声明：head 的 Content-Encoding，没有第二个 boolean gzip。
- HEAD/204/304 不读取 body source，这是类型和调用顺序共同保证的事实。

见 [ResponsePlan.java](freeway-http/src/main/java/com/jujin/freeway/http/ResponsePlan.java)、
[ResponseBody.java](freeway-http/src/main/java/com/jujin/freeway/http/ResponseBody.java)、
[HttpTransport.java](freeway-http/src/main/java/com/jujin/freeway/http/HttpTransport.java)。

### 请求：RequestHead

method、path、queryParams、headers 是四个请求事实。它们不应该在 RequestView 和
HttpTransport 各写一遍，也不应该由两个 support 各维护一套 header casing 规则。

    interface RequestHead { method, path, queryParams, headers }
    interface RequestView extends RequestHead { pathVar..., queryParam..., header... }
    interface HttpTransport extends RequestHead { framedBodyStream, TLS, writeResponse }

RequestViewSupport 是共同读取实现：HTTP 从 transport 读 live 快照，WebSocket 用构造时
不可变快照；header 统一 lowercase，path vars 由 router 写入。

### 错误：ErrorResponse + 纯 ErrorMapper

错误 mapper 的职责是判断异常对应什么响应，不是顺手把响应写出去。

    record ErrorResponse(status, code, message, details, headers) {
        void write(HttpResponse response); // 唯一写入点
    }

    @FunctionalInterface
    interface ErrorMapper {
        Optional<ErrorResponse> map(Exception failure);
    }

- mapper 不持有 HttpResponse，不能半提交。
- 一个 mapper 抛异常，服务器继续尝试下一个。
- 404/405/413/415/validation/500 共享 error + code + details 结构。
- 人类可读字段仍是 error；机器可读字段是稳定 code。

### 健康：HealthResult

健康检查不应该返回 Object，让每个调用方猜 JSON 形状。

    record HealthResult(boolean healthy, String detail) {}

HTTP /healthz 和 cloud readiness contributor 消费同一个结果类型：healthy 200，
unhealthy 503。路径可以不同，结果模型不能不同。

---

## 2. 读接口不暴露写能力

这是本轮最典型的一次“类型比运行时检查更诚实”。

Container.extension() 返回只读视图；add/seal 属于组合期 store，不属于消费者：

    interface Extension<T> {
        List<T> all();
        Map<String, T> asMap();
        void validateOrdering();
    }

    final class ExtensionStore<T> implements Extension<T> {
        Ordering add(String id, T value);
        void seal();
    }

- 应用不能通过 container.extension(...).add(...) 修改扩展点。
- 写入只有一条路：Binder.contribute(...)。
- “sealed 后再 add 会抛异常”从运行时防守变成编译期不可表达。
- 测试从“断言 late add 抛错”变成“这段代码根本编译不过”。

见 [Extension.java](freeway-ioc/src/main/java/com/jujin/freeway/ioc/extension/Extension.java)、
[ExtensionStore.java](freeway-ioc/src/main/java/com/jujin/freeway/ioc/extension/ExtensionStore.java)。

---

## 3. 名字只声称 holder 能验证的事实

### Scope.BOUNDARY，不是 Scope.THREAD

Scope.THREAD 从来不是 ThreadLocal，也不是线程生命周期；它是 ScopeBoundary.within(...)
的边界生命周期。名字在 virtual thread 和异步工作下会直接误导设计。

所以它叫 BOUNDARY：

- 一个 boundary 一个实例
- 由 ScopeBoundary.within 打开和关闭
- 不是“线程本地”，也不是“随线程死亡”
- 相关测试 fixture 也从 ThreadScopedDep 改成 BoundaryScopedDep

### framedBodyStream，不是 bodyStream

HttpTransport.framedBodyStream() 是原始分帧流；handler 的 HttpRequest.bodyStream() 是已经
套上 maxBodySize 限制的流。两者不同，名字必须不同。

### isCommitted，不是 isResponded

“响应已提交”是 transport 唯一知道的事实。support 不能猜，也不应该维护第二个 responded
标志。

### HealthResult，不是 Object

健康检查返回类型必须能说明 healthy/detail，而不是让调用方对任意 Map 做约定。

---

## 4. 默认值是承诺，不是方便

我们逐步删除“看起来方便、实际替调用方做决定”的默认值：

- HttpRequest.isSecure() 不再默认 false：TLS 请求不能因为实现忘了 override 就变成明文。
- HttpRequest.sslSession() 不再默认 null 表示“不知道”。
- HttpRequest.bodyStream() 不再默认全量缓冲：流式和物化不能由接口静默决定。
- HttpTransport 不再有 byte[]/stream/file 的 default body 策略：adapter 不能靠继承 default
  得到一个和 built-in 不同的 framing。
- FlowContextImpl 不再手写 VarHandle 懒初始化，改用已有的 LazyValue。
- FlowDriverDefault.Builder 不再存在：两个可选字段和“builder 不持有默认值”的约定冲突，
  而且没有调用者。

判断标准：

> 这个默认值是在表达一个不可变的事实，还是在替调用方做一个他可能不知道的决定？

前者可以保留；后者应该删除。

---

## 5. 结构先于检查

最好的修复不是“多一个 if”，而是“让错误的状态无法表达”。

- 每个请求一个 exchange：maxBodySize 是 HttpExchangeSupport 的构造输入，不是可忘记 reset
  的字段。
- ResponseBody 是 sealed：新 body 类型出现时，编译器强制 transport 补分派。
- RequestHead 与 RequestView 分层：transport 不需要实现它不知道的 path vars。
- Extension 只读：消费者不需要在运行时防守 late add。
- H2Dialect 不再继承 PostgresDialect：H2 mode 是 H2 的语法面，不是目标数据库身份；
  MERGE、INFORMATION_SCHEMA、DDL auto-commit 都属于 H2 自己。

“让错误无法表达”优先于“加检查”；“加检查”是最后手段。

---

## 6. 不要为了统一而统一

品味也体现在克制。

我们没有做这些合并：

- 不合并 EventBus、MeshEventBus、FlowEventBus：它们是 in-process、mesh、execution-local
  三个平面，语义不同。可以统一命名/订阅模型，不能统一生命周期。
- 不拆掉 HttpExchange 的复合入口：handler 需要 request + response + meta 同时在场。
- 不把 RouteIndex / WebSocketIndex 泛型化成一个 Index：WebSocket index 已经复用 RouteIndex；
  再抽象只会让两个协议互相污染。
- 不把 HttpServerHandle 合并进 HttpServer：engine handle 和 server 编排是两个生命周期。
- 不给 ErrorMapper 一个“顺手写 response”的 default：纯函数和副作用函数不能共用一个名字。

原则：

> 只有当两个东西共享同一个事实时，才合并；只共享“看起来像”的时候，保持分离。

---

## 7. Agentic 时代的要求

如果未来是 agent 在组装、探测和修复系统，API 的可发现性和可验证性比“少写几行”重要。

### 机器可读

- 错误有稳定 code，不是只有自然语言 message。
- 健康有 HealthResult，不是任意 JSON。
- Dialect 有 capability flags；H2 有 H2Mode。
- HttpEngine 的 honor contract 要“说得出”哪些字段能 honor，不能静默忽略。

### 无隐藏状态

- 不再让 handler 猜 isSecure / bodyStream / response 是否 committed。
- 不再让 mapper 在异常路径里偷偷写 response。
- 不再让 Map 的 put(null) 表示 remove 而不告诉调用方。
- 不再让 callAsync(deadline) 的默认实现静默丢掉 deadline。

### 结构化上下文

- Scope.BOUNDARY 是边界，不是线程。
- ContextExecutor 显式传播 ScopedValue。
- Flow branch 的 ThreadLocal 层未来应收敛到显式 branch context / ScopedValue，避免异步
  handler 丢失分支视图。

### 破坏是迁移路径

- 类型改了，编译器报错，调用方跟着改。
- 不保留两个名字、两个 default、两个生命周期。
- 一个 break 只有“删掉一个概念/一层”才值得；只改名的 break 是 churn。

---

## 8. 结构收敛轮的品味账本

| 决定 | 品味 |
|---|---|
| ResponsePlan + ResponseBody | 把“怎么执行 body”从接口 default 里赶出去 |
| RequestHead + RequestViewSupport | 四个请求事实只声明一次 |
| ErrorResponse + 纯 ErrorMapper | 错误形状是数据，不是副作用 |
| HealthResult 共享 | 健康结果不因模块不同而不同 |
| Extension 只读 + ExtensionStore 写 | 读接口不携带写能力 |
| Scope.BOUNDARY | 名字描述生命周期，而不是实现细节 |
| JsonCodec 容器内建 | 基础能力不属于 HTTP 模块 |
| H2Dialect 独立 + H2Mode | 兼容 mode 不是目标方言 |
| Orm.findAll 走 Dialect | 分页语法只有一个所有者 |
| LazyValue 替换手写 VarHandle | 已有正确原语，不再复制 |
| 删除 FlowDriverDefault.Builder | 没有调用者的 builder 是第二套默认值 |

---

## 9. 本轮：命名也是结构

这一轮没有新增业务能力，全部工作是在公共 API 上做“命名与契约对齐”。它不是换皮，而是在回答同一个问题：

> 读到这个名字的人，能否不打开实现就知道它是什么、缺值怎么办、会读还是会写？

### 9.1 领域词优先于机制词

- `HttpContext` → `HttpExchange`：一个请求/响应交换的完整生命周期，协作类型已经是
  `ExchangeHandler`、`ExchangeMeta`、`HttpExchangeEvent`，`context` 只是泛化实现词。
- `FlowEvaluation` → `FlowExecution`：它拥有 graph、engine、driver、context 和 stop
  信号，是一次 execution；`ExprEvaluator.eval()` 继续叫 eval，因为表达式求值和
  workflow execution 是两个动作。
- `DatabaseStats` → `PoolStats`：指标描述的是 pool，不是 database。
- `Health` / `Endpoint` → `ServiceHealth` / `ServiceEndpoint`：在 discovery 包里，
  service 是必要的领域限定词，泛名会让读者猜是哪个 endpoint。

### 9.2 包已经给出领域，类名不再重复领域

`cloud.rpc.RpcModule` 比 `cloud.rpc.CloudRpcModule` 少一层噪音；
`cloud.event.MeshEventBus` 明确是 mesh plane，而本地 `EventBus` 仍是本地 plane；
`cloud.discovery.registry.FleetRegistryClient` 把“registry fleet 的客户端”说完整，
不再需要 `RegistryNodeServiceDiscovery` 这种把同一词叠三次的名字。

原则不是“去掉所有前缀”，而是：

> `Cloud` 只出现在 umbrella/shared 边界；领域子包里由包名承担领域，类名承担角色。

因此保留了 `CloudModule`、`CloudHooks`、`CloudHttpClients` 这些真正跨领域的名字，
也保留了 `CloudHealthContributor` 这个角色名；只改了被包语义覆盖的模块类型。

### 9.3 返回契约必须写进方法名

- `Container.get(...)` 继续表示“找不到就抛”，这是容器主查找入口。
- `DatabaseRegistry.get(String)` → `find(String): Optional<Database>`；
  `FlowEngine.graph(String)` → `find(String): Optional<Graph>`；
  `graphOrThrow(...)` → `require(...)`。
- `find` 不再返回 null；`require` 才抛。名字让调用方在读完签名后就知道要处理缺失值。
- `Query.param(...)` → `bind(...)`：它是写入参数的动作，不是 getter。
- `CloudRequest.idempotentWith(...)` → `withIdempotent(...)`；
  `RpcExport.propagateMessages()` → `withPropagatedMessages()`；
  `RemoteProxyFactory` 的 `serviceId/mapping/timeout` → `withServiceId/withMapping/withTimeout`。
  `withX` 意味着返回新值，读起来不会误以为在修改调用方。
- `Container.isActiveBinding(...)` → `isActiveBindingMarked(...)`：
  它问的是“被选中的 binding 是否携带这些 marker”，不是“是否存在某个带 marker 的
  binding”；名字必须说出 actual predicate。

### 9.4 返回 body 的方法家族要能一眼区分

`HttpResponse` 原来的 `output`/`send` 同时承担了“body 来源、状态码、文本/JSON”三种差异。
本轮把它拆成可读的动作家族：

    body(byte[])
    body(InputStream, long)
    text(String)
    json(Object)
    file(Path, long, long)
    file(FileChannel, long, long)
    sendText(int, String)
    sendJson(int, Object)

- `body` 是内容字节和流；
- `text` / `json` 是内容语义；
- `file` 是 sendfile/资源来源；
- `sendText` / `sendJson` 是“状态码 + 内容”的快捷动作。

方法名不只要能编译，还要让调用点不需要读参数类型才能猜出行为。

### 9.5 读接口不暴露写能力

`FlowContext.stopped(boolean)` 从公共接口删除，移入 internal 的
`FlowContextState.setStopped(boolean)`。

公共 `FlowContext` 只保留：

    stop()
    isStopped()

停止信号的读取是应用 API，写入是 engine/execution 的内部权限。这和之前的
`Extension` 只读视图是同一个审美：读接口不应该携带写能力。

### 9.6 一次改名要完成整个家族

本轮没有只改一个“看起来最丑”的方法，而是把同一个事实的所有出口一起对齐：

- `DatabaseRegistry.find` 的 Optional 契约、实现、测试和文档一起改；
- `BatchQuery.rows/named` → `rowsRaw/rowsNamed`，连带接口、实现、测试；
- `Row` 的所有便捷访问器统一为 `as*`；
- `Sql.sql/setColumn` → `toSql/value`，连带 `Orm`、测试和文档；
- `CloudHttpClient` 改名时，`CloudHttpClientDefault` 和它的 `Wiring` 一并保持类型关系；
- `CloudEventBus.CloudEventStats` 与 `EventBus.EventBusStats` 都收敛为 `Stats`，不让嵌套
  owner 名再次 stutter。

不保留兼容 alias：旧的错误名字继续存在，等于第二个答案还存在。

### 9.7 克制同样是品味

没有被选中的、仍然表达正确语义的名字保持不动：`ModuleEx`、`HttpPipeline`、
`DbModule`、`HttpUtils`、`ContextExecutor`、`RuntimeDescription`、
`HttpRequest.param/pathVar`、`CloudHttpClients`、`CloudHealthContributor`。

原因不是“以后再说”，而是：

> 不值得用一次大范围 break 制造相同的公共词汇数量；只有当改名删掉一个第二答案，
> 或让一个契约/边界变得可读时，改名才成立。

`CloudEvents 1.0` 是协议专有名词，不随 `Cloud*` 类改名；`CloudHttpClients` 是跨 RPC /
registry / event 的共享 helper，不是 RPC 类型。名字的归属要按事实判断，不能按字符串前缀判断。

## 10. 本轮命名账本

| 决定 | 品味 |
|---|---|
| `HttpExchange` + `HttpExchangeSupport` | 用 exchange 描述请求/响应相遇的事实，而不是泛化的 context |
| `HttpResponse.body/text/json/file/sendText` | 内容来源、内容语义、快捷动作各自有词 |
| `isSecure` / `matchWebSocket` / `webSocketIndex` | predicate 用 `is`，WebSocket 在 Java 标识符里保持两段 camelCase |
| `PoolStats` / `DatabaseRegistry.find` / `databases()` | 统计、缺值、集合读取各自有准确词 |
| `Query.bind` / `Contribution.addDeferred` | 读、写、立即构造、延迟构造不再共享一个模糊动词 |
| `Row.asString/asInt/asLong` | accessor family 使用同一语法 |
| `FlowExecution` / `FlowEngine.run` | workflow execution 与 expression eval 分开 |
| `FlowContextState.setStopped` internal | 公共读接口不暴露 engine 写能力 |
| `metadata()/putMetadata()` | 读 map、读单值、写 meta 的动词一致 |
| `EventBus.Stats` / `MeshEventBus.Stats` | 嵌套类型不再重复 owner 名 |
| `ScopedCache.computeIfAbsent` / `JsonUtils.toJson` / `JsonCodec.coerce` | 副作用、序列化、类型转换各自用准确动词 |
| `MeshEventBus` / `MeshEnvelope` / `MeshSubscription` | mesh plane 有独立词汇，不与本地 EventBus 混名 |
| `RpcRequest/RpcResponse/RpcException/RpcHttpClient` | RPC 子领域内由包名承担 cloud，类名承担角色 |
| `RpcModule/DiscoveryModule/HealthModule/...` | 模块类型不再重复包名 |
| `ServiceHealth/ServiceEndpoint` | discovery 公共类型用 service 限定 |
| `cloud.discovery.registry.Fleet*` | registry fleet 的客户端/适配器在名字里说完整 |
| `CloudHttpClient` 改名连带 `CloudHttpClientDefault` | 接口和默认实现属于同一类型家族，不能留半套命名 |
| 保留 `CloudHttpClients` / `CloudHealthContributor` / `CloudEvents` | 不按前缀机械改名，按事实归属改名 |

## 11. Review checklist

在提交一个“重构/清理”之前，按顺序问：

1. 谁拥有这个事实？
   如果答案超过一个，先合并所有者，不要加同步检查。

2. 这个默认值是在表达事实，还是替调用方做决定？
   后者删除，或者改成显式 capability。

3. 这个读接口是否暴露了写能力？
   如果是，拆成 read view + composition store。

4. 这个名字是否声称了 holder 无法验证的东西？
   THREAD / configured / responded 都是反例。

5. 这个结构是否需要一段很长的注释才能证明安全？
   需要，就改结构；注释应该解释“为什么”，不是替结构道歉。

6. 这个 break 是删除了概念，还是只改了名字？
   只改名的 break 不值得；删除一层/一个默认/一个第二答案才值得。

7. 测试是在钉行为，还是在描述实现？
   删除实现后仍然绿的测试，通常没有守住契约。

8. 一个 agent 能否只读类型和错误码就知道下一步？
   不能，就补稳定契约，而不是补更多注释。

9. 这个方法名是否写清了缺值行为？
   `get` 抛、`find` 返回 Optional、`require` 抛，不要让调用方从实现猜。

10. 这个名字是否在包里重复了包已经表达的领域？
    包负责领域，类负责角色；除了 umbrella/shared，`Cloud*` / `*Module` 不应层层叠加。

11. 这次重命名是否完成了整个家族？
    一个 `output` 改名、一个 `Stats` 改名、一个 Default 改名，都要连带 interface、
    implementation、tests、docs 和所有 companion types。

---

## 12. 一句话总结

**Freeway 的美学不是“更少的代码”，而是“更少需要解释的结构”。**

一个响应只有一个 plan，一个错误只有一个 mapper，一个扩展点只有一个读入口，
一个作用域只有一个生命周期，一个事实只有一个所有者；一个 owner 只有一个名字，
一个公共名字只允许一个契约。

当结构正确时，注释会变短，测试会变硬，agent 和人都更少靠猜。
