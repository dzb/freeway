# freeway-cloud 模块审计（2026-09-03）

范围：`freeway-cloud`（82 个主源文件、24 个测试类 / 133 项测试），覆盖 discovery/registry、RPC 与
resilience、events 网格、context 传播、observe/health/secret/storage、配置面与文档一致性。
基线：`AUDIT-2026-09-03.md`（全项目审计）与 `git diff`（工作树含未提交变更）。
方法：逐文件静态审读 + 跨模块边界核对（freeway-http / ioc / boot / commons）+ **两处结论以
最小复现测试实证**（探针已删除，复现步骤记录在下文）。

## 结论摘要

| 严重度 | 数量 | 条目 | 状态 |
|---|---|---|---|
| P0 | 0 | 上一轮 P0（PeerHub CLASS 反序列化默认放行）已修复并有回归测试 | 已闭环 |
| P1 | 1 | RPC 导出：一个进程只能有一个 mapping，与 API/文档承诺矛盾且启动即硬失败 | 已修复 §1 |
| P2 | 5 | 分片文本帧丢失、RPC 拒绝路径控制字符放大成 500、`/rpc/*` 零门禁、`secret.*` 脱离配置级联、熔断探针结算的线程亲和约束 | 代码修复 2（§2.0/§2.1）· 文档收口 2（§2.3/§2.4）· 待决策 1（§2.2） |
| P3 | 11 | 资源与观测性卫生项，见 §4 | 代码修复 3（§4 之 2/3/6）；其余按需排期 |

整体判断：模块的工程纪律高于项目平均水准——安全边界有意识（token 常量时间比较、入站
三道门、路径遍历防护、peer 文本消毒），注释解释的是"为什么"而不是"是什么"。真正的问题
集中在**两个契约缺口**：RPC 导出面（P1-1）与控制字符/异常路径的状态码契约（P2-1），
以及 client 侧 WebSocket 帧处理这一处协议不对称（P2-0）。

---

## 1. P1 — `RpcEndpoint` 实际只能导出一个 mapping

**证据（实证）。** `RpcEndpoint.of()` 恒定返回同一路由模式：

- `rpc/RpcEndpoint.java:67` — `return Route.post("/rpc/{mapping}/{method}", endpoint::serve);`

`mapping` 是实例字段而非路径字面量，因此第二个 mapping 与第一个争夺同一个 trie 节点：

```
PROBE_RESULT: startup FAILED -> java.lang.IllegalStateException: Application startup failed
PROBE_CAUSE: java.lang.IllegalStateException: Duplicate route detected: POST /rpc/{mapping}/{method}
```

（复现：一个 `ModuleEx.bind` 里 `contribute(Route.class)` 两次，分别
`RpcEndpoint.of("user", bus, codec)` 与 `of("order", bus, codec)`，其余模块同
`RemoteCallerTest`。`route/RouteIndex.java:129-132` 抛错。）

**影响。**

1. 一个服务只能导出一个 call-topic 前缀。多领域模块各自导出自己的 mapping 是设计文档承诺
   的用法：`rpc/RpcEndpoint.java:22-23`「each mapping you hand to `of()` becomes
   reachable」、`docs/DEVELOPER-GUIDE.md:1620`（复数 "mappings"）、
   `docs/freeway-remote-callbus-design.md:164-166`。
2. 失败信息完全不指向真因——报错是 `Duplicate route detected`，而两处路径字符串在源码里
   看起来"参数化了"。开发者通常会花很久才定位。
3. 没有绕行方式：`serve()` 的前缀门 `!mapping.equals(mappingPath)`（`rpc/RpcEndpoint.java:81`）
   使单个 endpoint 只服务自己那一个前缀，注册两个又会撞路由。

**建议修复（向后兼容，URL 形状不变）。** 把 mapping 变成路径字面量，并在 `of()` 里即时校验：

```java
static String routePattern(String mapping) {   // RpcPaths，包内共享
    validateSegment(mapping, "mapping");       // 由 RemoteCaller 迁入，两侧同规则
    return "/rpc/" + mapping + "/{method}";
}
```

**已实施。** `RpcPaths.routePattern(mapping)` 承担"校验 + 拼字面量"，`RemoteCaller` 侧的
`validateSegment` 移入 `RpcPaths` 供两侧共用（URL 形状不变，调用方零改动）；`serve()` 中
`mapping` 路径变量与前缀比较随之路由字面量化而删除（同前缀已由路由保证）。回归：
`secondExportedMappingIsReachable`、`exportGateStaysPerMappingPrefix`、
`illegalMappingNameFailsAtExportTime`（`RemoteCallerTest` 的共享 fixture 现在一次导出
`user` + `order` 两个 mapping —— 这正是修复前无法启动的形态）。

---

## 2. P2

### 2.0 分片文本帧被丢弃并主动断链（仅 client 侧）

- `events/PeerConnector.java:307-313` — `if (!last) { webSocket.request(1); return null; }`
  注释写明「v1 frames are single-frame; partial frames ignored」：JDK
  `java.net.WebSocket.Listener` 按帧回调，非末帧的文本被**直接扔掉**。
- 后续 CONTINUATION 帧单独进入 `JsonUtils.parseObject` → 抛错 →
  `LOG.error("Frame handling failed...")` + `abort()`（`PeerConnector.java:318-321`）。
- 对端**会**分片：`freeway-http/engine/ws/WebSocketSessionImpl.java:133` 在文本超过
  `MAX_FRAME_PAYLOAD/4`（4 MiB）时改为分片发送（`:149-161`），入站上限 16 MB。
- 服务端入站则是**已合并的完整消息**（`engine/ws/WebSocket.java:60,186` →
  `websocket/WebSocketListener.onText(String)`，`events/PeerHub.java:246`）。

于是网格两侧对分片的处理不对称：4–16 MiB 的事件经 peer 服务端发出后，本节点必然丢帧并
反复 flap 该连接。自建两端都不易触发，但对端是 `freeway-ext` 或第三方 CloudEvents 实现时
就是数据丢失。

**已实施。** 重组逻辑落在新的包内类 `events/TextMessageAssembler`（单帧快路径、片段累积、
超限即释放缓冲并抛出），`PeerConnector` 的 listener 只在收到完整消息时进入解析，超限则
`abort()` 该连接；上限 `MAX_INBOUND_MESSAGE = 16 MiB` 与服务端入站限制同源。拆出独立类
是为了让这四条规则能在 `TextMessageAssemblerTest` 里被直接断言 —— 走真实 socket 需要
4 MiB 以上的载荷，作为单测不成立。

### 2.1 RPC 拒绝路径把控制字符放大成 500 + SEVERE 日志

**证据（实证）。**

```
POST /rpc/user/greet%0d%0aX-Injected%3a%20pwned%0d%0a HTTP/1.1
X-RPC-Version: 1
→ SEVERE Unhandled exception ... IllegalArgumentException:
  Header value must not contain control characters (offending char at index 31)
→ HTTP/1.1 500 Internal Server Error
```

CRLF 响应头注入**没有成功**：`freeway-http/AbstractHttpContext.java:268-278` 在写出前拒绝
CTL/DEL（HTAB 放行，符合 RFC 9110）。但代价是 `RpcEndpoint.reject()` 在
`ctx.setHeader("X-RPC-Reject-Reason", message)`（`rpc/RpcEndpoint.java:148`）处抛出，
400/404 契约被替换成 500。`method` 段来自 URL 解码后的路径变量
（`route/PathPattern.java:210,258`），攻击者只需变换 URL 就能：

1. 把任意"不存在的方法"探测变成 5xx，污染服务端错误率指标与告警；
2. 每次触发一行 SEVERE 全栈日志（日志放大）。

同一形状的还有 `encodeBusinessFailure()`：`propagateMessage=true` 时把 handler 消息写进
`X-RPC-Message`（`rpc/RpcEndpoint.java:138-141`），消息含 CTL（回显的用户数据、带换行的
SQL 片段）即炸成 500，业务异常类型丢失。模块里已有正确做法——
`rpc/RemoteCaller.java:167-170` 的 `sanitizePeerText`（剥 CTL + 200 字符截断），但它是
`private` 且在调用方一侧。

**已实施。** 采取"编码而非删字符"：`reject()` 与 `encodeBusinessFailure()` 写出的
`X-RPC-Reject-Reason` / `X-RPC-Exception` / `X-RPC-Message` 统一经新增的 `headerText()`
form-encode（这正是设计文档 §2.3 已约定的线格式，消费侧 `decode` 早已就位），诊断信息
不再被截掉；错误体改由 `codec.toJson(Map.of("error", …))` 生成，手写 `escape()` 删除。
`sanitizePeerText` 留在 `RemoteCaller` —— 它的职责是"对端文本进日志前消毒"，与这里不是
同一件事。回归：`encodedControlCharactersInPathStillYieldNotFound` 断言状态码仍是 404、
拒绝原因仍可达、且没有独立成行的注入头。

### 2.2 `/rpc/*` 框架侧零门禁，与 events 面不对称

`/cloud/events` 有三道独立入站门（token / allowed-types / allowed-topics，见
`docs/freeway-cloud-events-design.md` §4.3，`PeerHub.java:330-368,415-420`）。RPC 面只有：

- 版本头相等（`rpc/RpcEndpoint.java:71-75`）
- mapping 前缀匹配 + `callBus.handles(topic)`（`:81`）

任何能连到端口的客户端都能对被导出前缀下的**全部** topic 以任意参数发起调用
（`decodeArgs` → `callBus.call(topic, List.of(args)).join()`，`:86-101`），且是同步占用
服务端请求线程直到 handler 完成。设计文档把鉴权明确交给部署面
（`docs/freeway-remote-callbus-design.md:176`），但模块既没有一个 `rpc.token` 之类的开关，
也没有样例过滤器，"内部端点"这件事在代码里没有任何抓手。**未修复（需设计决策）。** 这是新增线格式契约（token 头？绑定？每调用还是每连接？），
而 `docs/freeway-remote-callbus-design.md:176` 明确把鉴权归属部署面。审计修复不应顺手
发明一套认证协议——留作显式决策项（与 `freeway.cloud.rpc.tls.*` 的 mTLS 面一起权衡）。

### 2.3 `freeway.cloud.secret.*` 是唯一脱离配置级联的两个键

- `secret/SecretSymbolSource.java:42` 与 `secret/CloudSecretModule.java:62` 直接
  `System.getProperty(...)`；全模块 36 个键中只有这两个不走 `SymbolSource.resolve`
  （对比 `events/CloudEventModule.java:99`、`rpc/CloudRpcModule.java:43`）。
- `freeway-boot` 的 env/file 层不会写 System property
  （`boot/internal/ConfigLoaderDefault.java:147` 读 `System.getenv()` 进自有层级），
  所以 `application.properties` 与 `FREEWAY_CLOUD_SECRET_KEYS` 两种写法**都静默无效**。
- `docs/freeway-config.md:4` 声明所有键参与统一级联，`:377-378` 两行也没有 "仅 `-D`"
  的批注。

后果正是该键想避免的那件事：白名单恒为空 → `SecretSymbolSource` 继续对**每个**符号名回答，
`@Symbol("path")` 取到 `$PATH`、`user` 取到 `$USER` 并压过配置文件（`:26-31` 自己列出了这个
碰撞面）。只有一行 WARN 提示"请把白名单作为 system property 设置"。

**已实施（文档路线）。** 功能路线本轮不走：在 provider 构造期经 `SymbolSource` 取自身键，
等于"在符号解析过程中重入符号解析"，而 ioc 侧没有"跳过本 provider"的原语，硬做会把一个
可用性问题换成解析栈的脆弱性。现由文档承担知情权 —— `docs/freeway-config.md` 密钥表两行
标注 **仅 `-D` 系统属性生效**（含原因），环境变量映射小节把这两个键列入例外，与"带连字符
的键不支持环境变量"并列；`:4` 的统一级联表述由此不再误导。若日后要做功能收口，前置条件是
ioc 先提供"解析自身配置时排除该 provider"的显式机制。

### 2.4 熔断半开探针的结算依赖线程亲和

- `internal/CircuitBreakerDefault.java:51` — `private final ThreadLocal<Long> admittedProbeEpoch`
  在 `allowRequest()` 里 `set`（`:99,118`），在 `onSuccess()/onFailure()` 里 `get` + `remove`
  （`:127-129,154-156`）。
- `internal/ResiliencePolicy.java:91` 的 `boolean probe = breaker.state() == HALF_OPEN`
  同样依赖调用线程上的状态。

当前同步链路（`orchestrate` 在调用线程内完成）成立；`callAsync`
（`internal/CloudHttpClientDefault.java:182-215`）把 `orchestrate` 送到虚拟线程上跑，
`RemoteCaller` 的 `orTimeout` 又在完成线程上结算，任何"在别的线程报结果"的用法都会让探针
"不计账"。缓解是 `openWindow` 重新 arm，所以表现是长期停在 HALF_OPEN 而不是永久 OPEN。

**已实施（契约文档化）。** `resilience/CircuitBreaker` 的类 javadoc 写明"结果必须由
准入它的线程结算"及其后果（探针不计账 → 停在 HALF_OPEN 直到 open window 重新 arm）。
把 epoch 做成 `allowRequest()` 的返回值再传回 `onSuccess(epoch)` 是更硬的形状，但那会改
`CircuitBreaker` 公共签名、波及 `freeway-ext` 侧的外部实现，作为独立 API 变更排期。

---

## 3. 已核对为"无问题"的疑点（避免重复排查）

- **事件回环 / 放大**：入站帧不回桥。`freeway-ioc/EventDispatcher` 在 `sendToSinks` 前有
  `!inbound` 判定，`PeerHub.receive()` 的自源丢弃（`fworigin`）另作双保险。
- **握手失败导致 peer 注册泄漏**：`engine/ws/WebSocket` 的读循环在 finally 中必发
  `onClose`，`PeerHub.register/duplicateToClose`（`:128-180`）幂等，未见泄漏。
- **token 计时侧信道**：`MessageDigest.isEqual` 常量时间比较（`PeerHub.java:415-420`）。
- **配置死键**：`CloudConfigKeys` 全部 36 个常量均有 ≥1 使用点；模块内无散落的
  `"freeway.cloud.*"` 字面量读取。
- **对象存储路径遍历**：`internal/ObjectStorageDefault` 的 `resolve`/`requireBucket` 收敛
  在挂载根内，未见绕过。
- **注册表驱逐竞态**：`internal/RegistryStore` 用 `compute` 完成空 service _map_ 清理，
  并有注释说明所修的竞态；`Entry.touch()` + 读时惰性驱逐（默认 30s）成立。
- **分层依赖**：`Metrics` SPI 位于 `freeway-commons`，cloud 使用它不构成反向依赖。

---

## 4. P3 卫生项（一次性清理即可）

1. `internal/CloudHttpClientDefault.java:94-95` — `breakers`/`rateLimiters` 以 serviceId
   为键、`computeIfAbsent` 只增不驱逐（`:223-224`）。serviceId 来自发现数据源，
   基数不受本节点控制。
2. **已修复** `internal/CloudHttpClientDefault.java` — `inFlight` 登记此前发生在任务提交
   之后，若 `close()` 在两者之间跑完遍历，被 shutdown 丢弃的排队任务无人结算
   （`RemoteCaller` 的 `orTimeout` 使调用方不会永久等待）。现改为在 `close()` 的监视器下
   先登记再提交，关闭窗口内的调用同步抛 `IllegalStateException`。
3. **已修复** `internal/RegistryLifecycleHook.java:72` — `stop()` 在 try 之外
   `container.get(ServiceRegistry.class)`，关闭期容器半拆时抛出的异常会掩盖真因。现按
   best-effort 降级为一条 WARN 并仍清理跟踪实例。
4. `events/PeerConnector.java:104-116` — `setPeers` 只增不减，运行期无法下线节点（残留
   dialer 持续重连）。
5. `events/PeerConnector.java:34-39` — 握手超时 10s 与重连退避基线 1s / 上限 30s 是私有
   常量，无对应配置键；`CloudEventModule.java:111` 传入的 3s 是**连接超时**（唯一可配的
   一个）。
6. **已修复** `rpc/RpcEndpoint.java:13` — `java.net.URLDecoder` 未使用的 import。
7. `internal/ResiliencePolicy.java:149-166` — `cloud.rpc.calls/failures/duration` 无服务维度
   （根因：`commons/metrics/Metrics.java:22-31` SPI 不支持 tag）。多下游网格中无法从
   `/metrics` 定位是谁在熔断，只能靠 span（`cloud.rpc.<serviceId>`）追。
8. `internal/ReadyHandler.java:50-77` — `CloudHealthContributor.check()` 无超时预算，
   任一 contributor 阻塞即 `/health/ready` 挂起（探针线程随每次探测堆积）。
9. `internal/ObjectStorageDefault.java` — `put` 忽略传入的 `ObjectMetadata`；etag 两套算法
   （写入侧内容 SHA-256 `:116,242-247`，读取侧 `size + "-" + mtime` `:190`），
   `If-Match` 语义跨读写不一致。
10. `context/Baggage.java:9-31` — 无条目数/字节上限；baggage 随每次跨边界调用与每条事件
    全量传播，单个恶意/失误的键即可放大载荷。
11. `internal/TracerDefault.java:162-166` — `restoreThreadState()` 在
    `!frame.stack.remove(active)` 时提前返回，跨线程 `close()` 后 ambient 上下文与 MDC
    不恢复（表现是后续日志丢 diagId，而非异常）。

---

## 5. 测试与文档评估

**测试。** 审计基线为 `mvn -pl freeway-cloud test` 133 项全绿（含上一轮新增的
`PeerHubInboundGateTest` 两例，正确区分了 CLASS 空列表 = 全拒 / TOPIC 空列表 = 全放行）。
修复后 141 项全绿。覆盖面缺口与本报告发现一一对应，前三项已随修复补齐：

- ~~无"多 mapping 共存"用例~~ → 已由 fixture + 3 条回归覆盖（P1-1 正是因此长期未被发现）；
- ~~无含 CTL/编码字符的 RPC 路径用例~~ → `encodedControlCharactersInPathStillYieldNotFound`；
- ~~无分片文本帧用例~~ → `TextMessageAssemblerTest`（真实 socket 需 4 MiB 以上载荷，不适合单测）；
- 无"关闭期在途调用"用例（P3-2 已修，测试仍缺 —— 竞态窗口难以稳定复现）；
- `RemoteCallerTest` 用 `System.setProperty` 设置超时，恰好绕开了 P2-3 的配置面缺口，
  这一点仍成立（现在文档已明说这两个键只能 `-D`）。

**文档。** 大部分口径与代码一致，且 §4.3（token 三道门、轮换需滚动重启、为何不内置默认
token）是本次审计里质量最高的一段。三处待修正的处理结果：

1. **已修** `rpc/RpcEndpoint.java` 与 `docs/DEVELOPER-GUIDE.md:1620`、
   `docs/freeway-remote-callbus-design.md` §3.2：多 mapping 承诺已随 P1-1 落实，三处表述
   同步为"每次导出各自的字面量路由"。
2. **已修** `docs/freeway-config.md`：`secret.keys` / `secret.file` 标注"仅 `-D` 系统属性"，
   环境变量映射小节补例外。
3. **撤回** 原先记作"`ResiliencePolicy.java:58` 的 javadoc 与 metrics 不一致"：细读后该
   行已把 `serviceId` 限定在"span name, failure messages"，并未宣称 metrics 分服务。指标缺
   服务维度是 P3-7 的 SPI 限制，不是文档矛盾。

---

## 6. 本轮结果

**已落地（`mvn -pl freeway-cloud test` 全绿，141 项）：**

- **P1-1** 多 mapping 导出 —— `RpcPaths.routePattern` + 导出期名字校验，fixture 现在一次
  导出两个 mapping；3 条新回归。
- **P2-1** 拒绝/失败详情 form-encode + 错误体走 codec；`escape()` 删除；1 条原始 socket 回归。
- **P2-0** `TextMessageAssembler` 接管分片重组（16 MiB 上限）；4 条单测。
- **P2-3** `secret.*` 的 `-D`-only 事实进入 `freeway-config.md`（表格 + 环境变量映射例外）。
- **P2-4** `CircuitBreaker` 同线程结算契约写进 SPI javadoc。
- **P3-2 / P3-3 / P3-6** 关闭竞态、钩子停止期异常掩盖、死 import。
- 文档同步：`DEVELOPER-GUIDE.md` RPC 导出段、`docs/freeway-remote-callbus-design.md` §3.2、
  `CHANGELOG.md` 的 Fixed / Documentation 条目。

**仍开放（各有前置条件，不建议在审计修复里顺手做）：**

1. **P2-2** RPC 入站门禁 —— 需要新的线格式契约决策（token 头 / 绑定面 / 粒度），当前设计
   文档把鉴权归属部署面。
2. **P2-3 的功能形态** —— 需要 ioc 先提供"provider 解析自身配置时排除自己"的原语。
3. **P2-4 的形状改造** —— `allowRequest()` 返回探针句柄是破坏性 API 变更，涉及外部实现。
4. **P3-1 / P3-4 / P3-5 / P3-7 / P3-8 / P3-9 / P3-10 / P3-11** —— 分别为无界 shard map、
   `setPeers` 只增不减、硬编码超时/退避、metrics 无服务维度（受 `Metrics` SPI 无 tag 限制）、
   health check 无超时预算、etag 双算法与 `ObjectMetadata` 被忽略、`Baggage` 无上限、
   tracer 跨线程 close 后不恢复 ambient。都属可排期的独立小改动，不是缺陷放大面。

> 工作树说明：本报告的代码行号基于审计时状态；上述修复落地后 `rpc/RpcEndpoint.java`、
> `rpc/RpcPaths.java`、`rpc/RemoteCaller.java`、`events/PeerConnector.java`、
> `internal/CloudHttpClientDefault.java`、`internal/RegistryLifecycleHook.java` 及
> `resilience/CircuitBreaker.java` 已随之变动，新增 `events/TextMessageAssembler.java`。
> 另有**非本次审计产生**的未提交变更（`internal/MetricsDefault.java` 内部类改名、根
> `pom.xml` 移除 starter 依赖声明、events 门禁相关文档），本轮未触碰。
