# freeway 整改方案：正确性、产物说真话、机制归一（1.5.6-SNAPSHOT）

> **判据优先级：代码 > 测试 > 文档。** 本文每条发现都标注**证据类型**。
> 行号仅供定位，**不构成判据**——代码一动即漂移，判据必须能在不读任何注释的情况下复核。
> 标注为「实测」的条目附有可独立运行的探针，已在 1.5.6-SNAPSHOT 上跑出实际输出。

审计对象：七个核心模块（commons / ioc / boot / http / db / flow / cloud）的 `src/main`。
方向：**先修会静默出错的地方 → 再修说假话的产物 → 再归一重复机制 → 最后补完备性**。
`freeway-ext` 不在本轮范围。

配套 `docs/audit-philosophy-modernity-1.5.2.md`（理念与现代性）——本文不重复理念层结论，
只处理**行为正确性、文档与实现的一致性、同一机制的多份实现、能力缺口**四类。

## 0. 结论速览

1. **两处会静默出错的行为缺陷**（S1，均已修）：`@PostConstruct` 在最自然的绑定写法下**跑两次**；
   `Sql.setColumn` 把 `Sql`/集合值**绑成 JDBC 参数**而不是内联/展开。二者都不报错。
2. **产物说假话**（S2，4 处已修：body 限制、S2-2 见下、S2-3 改文档、S2-4），其中一处已经污染了仓库自己的规则文件：`AGENTS.md:227` 把
   `AbstractHttpContext.readBody` 点名为 413 的**共享实现**，而内建引擎根本不用它——
   同模块两套独立的 body 限制实现，两份文档都指向死的那套。
3. **真冗余只有一处类别**：同一机制的两份实现，共 10 组（§4）。其中 3 组有实质差异或
   已被规则文件引用，属高优先。
4. **两处完备性缺口**（S5）：`HttpModule` 不为请求开作用域（`Scope.THREAD` 机制齐备、
   fail-loud、但无人开）；`CloudEventBus` 无有序/异步发布——跨 JVM 顺序比进程内更值得保证，
   顺序机制却只在进程内总线上。
5. **本轮撤回 5 条误判**（§6）。上一轮以"零生产调用方"为删除理由的判断大部分不成立——
   freeway 是开发基座，仓内 grep 只能证明"本仓未验证"，不能证明"场景不成立"。
   这条教训已固化为 §1.2 的新增判据。撤回项本身是正面发现：
   `EventStreams` 返回的是 JDK 的 `Flow.Publisher`；`EventStats` 已桥接 `Metrics`；
   `cloud/storage` 的三处非对称实现全部写明了理由，是能力门控而非隐瞒。
6. **不建议做的删除**（§7）：AOP 包、`Scope.THREAD`、`cloud/storage`、`PlantUml*` 定制层、
   `EventStreams`/`publishAsync`/`publishOrdered`/`EventStats`、三个 HTTP/2 帧类。

## 1. 判据与方法

### 1.1 判据来源

沿用 `AGENTS.md` 的守则，不引入审计者私货：

- **产物包含理由 / loudness**：删除若可能让既有配置静默失效，必须在启动期自报并点名修法。
  反向地，**文档指向一个不存在的实现，等同于产物说假话**，按同一条处置。
- **兼容不是目标**：无兼容 shim、无旧 arity 保留；形状变更以编译错误作为迁移路径。
- **机制与内容分层**：一个机制有两份实现 = 概念重复，两份都该消失或合并到一处。
- **抽象当场自证**（`audit-philosophy-modernity` §1）：新增扩展点须有仓内锚点或指名消费者。

### 1.2 证据类型（每条发现必标其一）

判据的强度自上而下递减。**只有「实测」与「代码事实」能作为整改依据。**

| 标记 | 含义 | 判据强度 | 复核方式 |
|---|---|---|---|
| **实测** | 写了探针并跑出实际输出 | 最强 | 重跑探针，输出应一致 |
| **代码事实** | 仅凭调用图/控制流即可判定，不读任何注释 | 强 | 读代码路径，不看 javadoc |
| **文档漂移** | 代码语义正确，是注释/文档与之矛盾 | 中 | 改文档即消失，**代码不动** |
| **子审计** | 来自并行深审，未逐条复核 | 弱 | **修复前必须重新确认** |

**「文档漂移」与「代码事实」必须分开**，因为它们的整改成本差一个量级：
前者只改注释，后者要改实现并补回归测试。混在一起会导致把改注释的活当成改代码来做，
或反之。

**行号的地位**：本仓的行号会随任何编辑漂移，因此本文的每条判据都写成**行为陈述**
（如"存在两条 `initialize` 路径可达同一实例"），行号附在其后仅作定位便利。
判据在代码重构后仍应成立；若某条判据只在特定行号下为真，它是脆弱的，应重写判据而非重排行号。

### 1.3 新增判据（取代"零调用方"）

判断一个公开成员是否冗余，必须同时回答：

- **(A) 场景对开发基座是否成立？** 消费者按设计住在 `freeway-ext` 与应用里，不在本仓。
  仓内零调用方**不构成**删除理由。
- **(B) 是否已有同类能力机制？** 只有 A 成立且 B 不成立，才算"未兑现的扩展点"；
  A、B 都成立才是**真冗余**。

由此得到的三分类处置：

| 分类 | 判据 | 处置 |
|---|---|---|
| 真冗余 | A 成立 + B 成立 | 合并到一处，删除另一份 |
| 完备性缺口 | A 成立 + B 不成立 + 无人接线 | **接线**，不删能力 |
| 产物说假话 | 文档/注释与实现相反 | 改文档或改实现，二选一并记理由 |

### 1.4 方法与复核口径

分模块深审由四个独立子审计并行完成（各自只读）。随后本人对 S1/S2 全部条目做了
**执行级复核**——不满足于读代码，为每条写探针在当前 SNAPSHOT 上实跑。

**这一步改变了三条结论**：

- S1-1、S1-2 由「读代码推断」升级为**实测**；
- **S2-2 的严重性被实测推翻**（见下），原描述高估了一个数量级；
- **§6 的 `cloud/storage` 撤回结论虽正确，但推理链混入了不必要的文档依赖**——
  纯代码即可判定（见 §6 说明）。

§4/§5 中标「子审计」的条目**修复前必须重新确认调用方**（尤其 `freeway-ext`），
且应先补探针升级为「实测」再动手。

量化只用于指路。本轮踩到并纠正的量化假阳性记在 §6。

## 2. S1 — 静默出错（最高优先）

### S1-1 `@PostConstruct` 在 `.to(c -> c.create(X))` 下跑两次 — ✅ 已修

**证据类型：实测**（1.5.6-SNAPSHOT 实跑，输出见下）

**判据（不依赖行号）**：存在两条 `initialize` 调用路径可达同一个实例——
provider 路径经 `directInstance` 调 `materialize`→`container.initialize`；
而 `create` 自身已 `constructInstance` + `initialize`。二者之间无幂等保护，
故 `to(c -> c.create(X))` 的实例被 initialize 两次。

**探针与实际输出**（`@PostConstruct` 计数自增，其余依赖已剥离以隔离变量）：

```java
m.bind(Svc.class).to(x -> x.create(SvcImpl.class));
// SvcImpl 内：@PostConstruct void init() { PC.incrementAndGet(); }
```

```
realized: hi
@PostConstruct 次数 = 2  (期望 1)
```

**最终修法与本文初稿不同**（两处修正记录在案）：

1. **初稿方案 (a)「让 owner-scoped `create` 不再 initialize」不成立**——改完
   20 个测试挂掉，全是 `container.create(...)`：公开路径也委托到同一方法，
   拿掉它的生命周期等于改变 caller-owned 语义。**回归测试当场挡住了"看起来通过"。**
2. **初稿方案 (b) 幂等化需要一个「已初始化」记录集合**。第一版实现用了**容器级**
   `IdentityHashMap`——**错的**：那会让容器持有每一个经 `create` 造出的实例，
   而那些实例按设计不由容器管理（`Container.create` 的 javadoc：caller owns the
   lifecycle）。作用域必须是**单次 realize**（`ThreadLocal`，退出即清）。

**真正的修法是划清归属，而不是加记账**：`@PostConstruct` 属于**受管**的那半段生命周期，
而 `Container.create` 是**裸 `new` 加依赖解析**（给构造参数与 `@Inject` 字段，不给生命周期）。
理由不止于消除重复——`@PostConstruct` 的典型内容（开连接、注册回调、起线程）
必须配对 `@PreDestroy`，而 `create` 造出的实例不进服务缓存、`Shutdown` 不遍历它，
**那半截生命周期在设计上就无人配对**。去掉它之后契约自洽了。

**附带修正**：`Contribution.add(Class)` 的 javadoc 承诺 invoke `@PostConstruct`，
而贡献同样不参与 `Shutdown` 遍历。贡献路径因此显式调 `ContainerImpl.postConstruct`
保住该契约——单边性（能启动、无人停止）是贡献的既有约定，不在本次整改范围。

**变更范围**：`Container.create(Class)` 签名不变（编译期无感），
javadoc 移除 "and `@PostConstruct`" 一项；其余四句保留。属 `AGENTS.md`
「无编译错误保护的行为变更」，已写 CHANGELOG Fixed + Migration 双条目。

**回归测试**（`SingleInitializationTest`，9 例 + `ExtensionAggregationTest` 1 例）：
- 四种 realize 形状各一次：`to(Class)` / `to(c -> new X())` / `to(c -> c.create(X))` /
  **`bind(Concrete)` 不带 `to`**（`provider == null` 分支，初稿遗漏、由追问补上）
- 公开 `create` 不再 postConstruct，但**仍做字段注入**（`@Inject` 是它强于 `new` 的部分）
- caller-owned 实例不被容器任何 Map 字段持有（反射检查，非 `System.gc()`）
- `contribute(X.class)` 仍发 `@PostConstruct` 恰一次

**结果**：ioc 304 例（原 293）、全仓 2145 例全绿。

### S1-2 `Sql.setColumn` 不内联 `Sql` 值 — ✅ 已修

**证据类型：实测**（1.5.6-SNAPSHOT 实跑 + H2 端到端）

**判据（不依赖行号）**：值拼接存在两条路径——`setExpression` 走 `normalizeArgs`，
其中 `appendValue` 对 `Sql` 值做内联展开；`setColumn` 把值**原样存入** `dmlValues`，
渲染时恒为 `?`。而 `setExpression` 是 UPDATE-only、`setColumn` 是 INSERT-only，
因此 **INSERT 场景没有任何途径内联子查询**。

**探针与实际输出**：

```
[setColumn 标量]        INSERT INTO t (name) VALUES (?)            args=1
[setColumn 传 Sql]      INSERT INTO t (name) VALUES (?)            args=1
   绑定参数实际类型: Sql          <-- Sql 对象被当成 JDBC 参数
[setExpression 传 Sql]  UPDATE t SET name = SELECT x FROM other    args=0   <-- 正确内联
```

**修法与一处关键约束**：INSERT 是**位置对应**的——`dmlTargets` 与 `dmlValues` 平行、
每列一个条目，渲染时按条目拼逗号。所以内联子查询的文本成为**该列的条目**，
而它的占位符留在文本内部。**若把子查询的参数也塞进 `dmlValues`，会多渲染一个 `?`
并让后续列错位**——这是实现中踩到的真实错误（首版渲染出 3 个 `?` 而非 2 个）。
最终形态是 `InlineValue(text, params)`：文本进列列表，参数由 `args()` 按位置摊平。

**回归测试**：
- `SqlTest` 5 例——内联单查询、双查询、无参数子查询、UPDATE 路径对照、标量不受影响
- `AutoIncrementTest.insertSelectFromASubqueryLandsTheRow`——**真实 H2 端到端**：
  建表、插数据、`INSERT … VALUES (SELECT …)` 执行、断言子查询的值真的落库。
  单元测试只证明 SQL 文本正确，端到端才证明语句可执行

**结果**：db 510 例（原 504）、全仓 2153 例全绿。

### S1-4 并行 join 的记账非原子 → 已完成的 join 被标上死胡同 — ✅ 已修

**怎么发现的**：不是读代码读出来的，是**全量跑时偶发**——`FlowEngineTest` 的
`parallelBranchesConvergeOnInclusiveGatewayOnce` 在 flow 模块 3 轮里挂了 1 轮，报
`FlowException: dead end at node 'gw'`。当时我只改了 markdown，所以这不是本轮改动引入的；
但"偶发"不等于"可忽略"，它是一条真实路径。

**根因**：`joinArrived` 的两件事**分属两个结构、更新它们不是一步**——

```java
int arrived = execState.countIncr(gw);      // AtomicInteger，各自原子
if (arrived >= expected) {
    execState.countSet(gw, 0);               // 同一个 AtomicInteger
    execState.deadEndClear(gw);             // 另一个 ConcurrentHashMap.newKeySet()
    return true;
}
markDeadEnd(gw);                             // 同上，另一个结构
```

能发生的交错：分支 A–E 各自 `countIncr` 得 1..5 后被切走；分支 F `countIncr` 得 6 →
清标记 → 激活网关 → 后继执行 → **运行正常结束**；此时 A 恢复，把它的**临时标记**写上去。
运行收尾检查读到这个残留标记，报"join 网关没收到全部分支"——**而实际一条分支都没丢**。
文案把一个记账顺序问题描述成了丢分支，排查方向会被带偏。

**修法**：整个 join 转换收进 `ExecState.join(graph, nodeId, expected)`，在该节点自己的
监视器下完成"计数→判定→标记/清标记"；`resetLoopBodyJoins` 的重置也改走同一监视器
（`joinReset`），否则嵌套并行里"重置迭代"与"分支到达"仍会交错。`ExecState` 里的
`countIncr` 随之消失——**计数不该能被单独操作**，它只在 join 转换里有意义。

**顺带删掉 `ExecState.deadEndClear`**：它正是那个不安全操作本身（单独清标记、不做配对的
计数变更）。清理权并入 `join`/`joinReset`；留着它等于把成因重新开放给下一个调用方。

**探针有效性**：`JoinMarkerAtomicityTest`（6 分支 × 400 轮）**修复前首次运行即失败**
（0.048s），修复后 0.085s 稳定通过。窗口很窄（约千分之一的交错），这正是它能以"偶发失败"
存活下来的原因——**单次通过不能证明修复**，所以探针必须跑到失败为止。

**方法论补记**：这条与本轮已记录的三次"长得像就是同一机制"是**镜像**的错误——那几次是
过度判定，这一条是**判定不足**：全量绿被我当作"通过"读了两次，而它其实是概率性的。
"全绿"与"已证明无缺陷"不是一回事，尤其在并发路径上。

**结果**：flow 110 例（原 109）、全仓 2199 例全绿；flow 模块连跑 3 轮 + 全仓连跑 2 轮稳定。

## 3. S2 — 产物说假话

### S2-1 body 限制有两套实现，文档都指向死的那套 — ✅ 已修（结论有一处自我修正）

**证据类型：代码事实（零调用方）+ 文档漂移（契约文本）**

**代码事实（判据核心，不依赖任何注释）**：`readBody` 零调用方——全仓仅 3 处命中：
`HttpEngine` 的 javadoc `{@link}`、方法定义自身、以及 `drainUnreadBody` 的
**同名不同义**命中。活路径是 `RequestBody.LimitedInputStream`。

**文档漂移**：`HttpEngine` 的跨引擎契约、`AbstractHttpContext` 自身 javadoc、
以及 **`AGENTS.md:227`**「Regressions to Watch」——规则文件把一个零调用方的实现
写成了适配器回归契约。

> **⚠️ 本条的一处自我修正**：初稿断言两份实现在"恰好等于 limit"与"filter 中途收紧"上
> **已经漂移**。实测**证伪**了这一点——穷举 limits × lengths × 块大小的全部组合后，
> 两份实现行为**完全一致**。所以这不是活 bug，而是**未被强制的保证**：
> 一个契约，两条独立实现，仅靠它们碰巧相符。
>
> 教训与 S2-3 同源：**"看起来像 bug"不是证据**。我当时甚至写了独立探针去
> 构造分歧，探针本身有 bug（简化版的 `newRead` 才是有问题的那个），差点据此
> 写下一个不成立的结论。修法不变，但**理由要如实**。

**最终修法**（审计方案 a）：`LimitedInputStream` 提取为 `http.internal` 下的顶层类
（`internal` 正是"ownerless 共享助手"的位置——共享方是根包的 `AbstractHttpContext`，
而 Java 无子包可见性），`readBody` 改为一行委托。保证从"碰巧相符"变成**结构性事实**。

**回归测试** `BodyLimitParityTest`（5 例）：跨边界矩阵 {7 limits × 9 lengths}、
分块读取 {3 块大小 × 3 limits × 长度窗口}、恰好等于 limit、超限 1 字节、
每次读都重查 limit（含中途放宽）。**逐案对比两条路径**而非各自断言——
这才是缺失的性质：任一侧单独漂移，各自的测试仍会通过。

**结果**：http 461 例（原 449）、全仓 2169 例全绿。

### S2-2 关闭与 `get()` 并发时向封印后的容器发布代理 — ✅ 已修

**证据类型：实测**（20000 次竞态复现 1 次；修法经二次实测迭代）

> **本条严重性经实测下调过一次。** 初稿据 `ServiceRuntime.get` 单独阅读写作"关闭后
> `get()` 返回活代理"——**这是错的**。单线程 `close()` 后再 `get()` 正确抛异常
> （`ContainerImpl.get` 有 `requireOpen()` 这道关卡，初稿漏看）。真实缺陷窄得多。

**判据（不依赖行号）**：`get()` 无锁而 `close()` 有锁。缓存未命中后铸代理，
若这个"铸 + 发布"整体落在 `close()` 清空之后，代理就被写回**已封印**的 `proxyCache`。

**修法迭代（两次，均有实测支撑）**：

1. **入口加一次锁外 `isClosed()` 快速拒绝**——**不够**。修复后测试从 attempt 3
   变成 attempt 31 仍失败：检查本身仍在"铸代理"之前，TOCTOU 窗口只是从
   "整个 close"缩到"一次 volatile 读"，没有消除。
2. **把"仍开着？"与"发布进 proxyCache"合成 `realizeLock` 内的一步**——正确。
   缓存命中路径保持无锁（那条路径不写任何状态）；`seal()` 本就持有 `realizeLock`，
   所以清缓存与铸代理互斥。PROTOTYPE 分支补同样的检查：它在封印后构造的实例
   永远收不到 `@PreDestroy`。

**一处必须保留的检查**：`realizeThreadScoped` 里的 closed 检查**不能**因为
"入口已检查"就删——THREAD 作用域的代理存活期**长于**产出它的那次 `get()`，
其目标在**首次方法调用**时实现，调用根本不经过 `get()`。
既有测试 `threadScopedProxyRejectsInvocationAfterClose` 立刻抓住了这次误删。

**回归测试的一处自我修正**：初版测试把 `get()` 与随后的调用放进同一个 catch，
于是"泄漏的代理调用抛异常"被归类成正确的 "closed" 而**掩盖了 bug**——修复移除后
测试竟"通过"。改为分别记录两步，并断言真正的不变量：
**close() 完成后 `proxyCache` 必须为空**。回退修复后 attempt 0 即失败。

**明确不算缺陷的行为**：调用方在 `close()` **之前**拿到代理、`close()` 之后才调用它
——那个代理是合法返回的，调用时抛异常是所有作用域共同遵守的契约。
测试把"预先 warm 缓存"留给这个区分，否则合法抛异常与泄漏铸代理无法分辨。

**结果**：ioc 305 例（原 293）、全仓 2153 例全绿。

### S2-3 `Schema.ensure` 的返回值与索引 — ⚠️ 结论推翻，改为修文档

**原判（错误）**：曾认定 `ensure` 的 `INDEX` 分支缺 `executed++` 是缺陷，
应补上并让返回值统计全部 DDL 语句。

**证据类型：代码事实，但判据读错了**（`executed++` 分布：TABLE 1、COLUMN 1、INDEX 0）

**实测推翻过程**：按原判补上 `executed++` 后，`freeway-db` **4 个既有测试失败**，
其中 `SchemaTest.ensureCreatesIndexOnNewTable:402` 的断言消息直接写着
**「should create 1 table (indexes not counted)」**，`ensureIndexIsIdempotent:445`
要求第二次 `ensure` 返回 0。**索引不计入是被测试固化的有意语义，不是遗漏**——
索引是表的配套物而非结构变更，所以"schema 是否变了"这个问题上它不该计数。

**真正的缺陷在 javadoc**：`@return number of DDL statements executed` 说"统计
执行的 DDL 语句数"，与实现及上述测试的既定语义**直接矛盾**。这条文档才是
drift 的源头——它让读者以为索引不计数是疏漏。

**最终修法**（代码不动）：
- `@return` 改写为「schema **changes** applied——创建的表与新增的列。
  **索引会被执行但不计数**：它是表的配套物而非对表的变更，实体未变时第二次
  `ensure` 必须报 0，即使重新 assert 了索引」
- 新增 `SchemaEnsureIndexCompatibilityTest.createdIndexIsExecutedButNotCountedAsAChange`
  从另一侧钉住契约：CREATE INDEX 确实执行了（1 条 DDL），而返回值是 0

**教训**：这一条与 S1-1 的教训同源。`executed++` 分布不均**看起来**像遗漏，
但"看起来"不是证据；既有测试的断言消息比我的推断更可信。
**读测试的断言消息**——它是设计意图的固化，比重新推断便宜得多。

### S2-4 `AppRuntime` 文档夸大跨线程关闭语义 — ✅ 已修（纯文档）

**证据类型：文档漂移**（代码语义正确且安全，是注释与之矛盾）

**代码事实**：`AppRuntimeDefault.start()` 与 `close()` 均声明 `synchronized`，
`start()` 全程持锁。**只有同线程重入**（`shutdownAttempted` 分支）被处理；
跨线程 `close()` 是**阻塞**到启动结束。

**修法**：接口 javadoc 改为写明这个区分——同线程展开、跨线程等待，并**说明为什么
等待是安全的方向**（展开会让运行时短暂处于 `STARTING` 且 hook 半途而无从收尾）。
**代码不动**。无回归测试（本条只改注释）。

### S5 遗留 — ✅ 已修：HTTP/2 帧的 `writeTo`

**证据类型：代码事实**（读 `writeTo` 实现即可判定，不需执行）

`DataFrame.writeTo` 只写 payload、**不带帧头**；`HeadersFrame.writeTo` 解析了
pad-length、PRIORITY 与 padding 却全部丢弃。这些方法在服务器实现里无生产调用方
（响应头块走 `HPackContext`），但**没人调用的错代码比没有代码更糟**。

**修法**：`DataFrame` 补帧头 + pad-length + padding；`HeadersFrame` 补全部三部分，
并**新增 `weight` 字段**（此前解析后未留存，无法重建）。帧长一律重算而非复用解码时的
长度——后者含已剥离的字节。

**回归测试** `FrameWriteToRoundTripTest`（7 例）：判据是**解析→序列化→再解析**，
因为这是唯一能检验「无人调用的 writeTo」的诚实标准。**回退修复后 5/7 失败**，
失败信息给出具体字节数差异（如 `expected: <14> but was: <5>`）。

### §7 遗留 — ✅ 已修：PlantUML display 函数的静默吞异常

`Graph` 两处 `catch (Exception ignored)` 把用户 display 函数的异常吞掉、改用默认渲染
——产出格式完好但完全错误的图，屏幕与日志无任何线索，看起来就像那个函数"没生效"。

**修法**：抽 `applyDisplayFunc`，改为抛出并点名三种真实结局
（`of` / `ofDefault` / `HIDDEN`）。这是本仓唯一两处「静默吞用户回调异常」。

**顺带修正一个自己引入的错误**：错误信息里用了 `link.from()` / `link.to()`，
而 `Link` 的访问器是 `prevId()` / `nextId()`——编译通过但运行期抛
`NoSuchMethodError`。**新写的代码同样需要实测**，`PlantUmlDisplayFailureTest`
当场抓到了。

## 4. S3 — 同一机制的两份实现（真冗余）

按 §1.2 的 (B) 判据。标"子审计"的条目修复前需再确认调用方。

| # | 机制 | 两处 | 差异/影响 | 优先级 |
|---|---|---|---|---|
| R1 | body 限制 | `AbstractHttpContext.readBody` / `RequestBody.LimitedInputStream` | 即 S2-1，✅ 已修 | ~~高~~ |
| R2 | mesh 帧分类 | `PeerHub` `onText` / `PeerConnector` `onText` | ✅ 已修：抽 `MeshFrame.classify(text, handshaken)`（密封接口 6 态），两腿只留各自的处置 | ~~高~~ |
| R3 | 指数退避 | `PeerConnector.backoffSleepMs` / `RetryerDefault.backoffMillis` | ✅ 已修：新增 `resilience.Backoff`，mesh **补上 jitter**（它才是滚动重启后同步重连的元凶）。配置键与第一跳语义各自保留 | ~~高~~ |
| R4 | 拓扑排序 | `Extension.order()+findCycle`（ioc，约 150 行） / `DeferScope.sort()`（commons，约 60 行） | ❌ **结论推翻**：算法层就不同，不是"算法相同、策略不同"。ioc 用 `PriorityQueue(positions::get)` 按注册位置破平局（**稳定**拓扑序），commons 用 `ArrayDeque` FIFO。破平局规则正是拓扑排序的产出本身 | ~~中~~ 撤回 |
| R5 | WS 分片重组 | `cloud/event/TextMessageAssembler` / `http/engine/ws/WebSocketReadLoop:33-103` | ❌ **结论部分推翻**：不是同一机制的两份实现。两侧分处连线两端——JDK 客户端已把分片边界交给 `onText(data, last)`，服务端要自己解帧、验掩码、按**字节**计数；cloud 侧按**字符**计数。常量不是共享副本。只修注释（见下） | ~~中~~ 仅注释 |
| R6 | SQL 条件方法 | `Sql.Group` 逐字复制 `Sql` 自己的 6 个方法 + 1 个私有 | ⚠️ **实测修正**：`Sql` 不可变（复制列表返新实例）、`Group` 可变（原地追加），6 个公有方法**不是**复制。真的重复只有 `addGroupedCondition` 的 7 行 | ✅ 已修（仅该 7 行） |
| R7 | 惰性持有 | `LazyHandler:36-50` / `LazyEndpoint:41-55` | 同构双检锁。**判定为不值得抽**（见下），仅在审计留档以免重复提出 | 低 → 不改 |
| R8 | 前缀展开 | `RouteGroup.expand()` / `WebSocketGroup.expand()`，且 `HttpModule:92-96` 与 `WebSocketIndex:37-43` **各展开一次** | 同一组 WS 路由启动时展开两遍。**属实，但无害且不可"修"**：两条路径的索引顺序本来就不同（HTTP 显式在前、WS 组展开在前），改成"传展开后的列表"会变更该顺序。改为把"为何无害"成文（见下） | 低 → 仅注释 |
| R9 | 图规范化 | `GraphSpec.create():185` 与 `Graph` 构造器 `:29-30` 各调一次 `normalize()` | `new Graph(GraphSpec)` 只有 `create()` 一个调用方，`Graph.java:26-28` 的注释在防守一个不会发生的分叉 | ✅ 已修 |
| R10 | 健康端点 | `CloudHealthModule:32` 硬编码 `{"status":"ok"}` / `HealthCheck.ALWAYS_OK:18` | ⚠️ **实测发现比初稿更重**：不是"绕过 codec"，是 **`Content-Type` 实测为 `text/plain; charset=utf-8`**——`HttpResponse.send(int,String)` 的默认类型，而 `/health/ready` 与 `/healthz` 都是 `application/json`。同一模块的三个探针端点，两个说 JSON、一个说纯文本 | ✅ 已修 |

### R4/R5/R6/R9/R10 的实测更正（记录在案）

这五项里三项的初稿结论站不住，且**错法是同一个**：读到"两处长得像"就当成同一机制，
没有去看那个**区分二者的细节**。这是本轮第三次同类错误（见 §6），因此单列。

**R4 的初稿有两处事实错误。** ① commons 侧"记日志、降级为注册顺序、再抛"——实测
`DeferScope.sort():181-185` 是**直接抛**，无日志、无降级；"降级为注册顺序"说的是
*无约束*动作（`sort():200-206` 的第 2、3 段），与环检测无关。② "算法相同"——ioc 的就绪集
是 `PriorityQueue(Comparator.comparingInt(positions::get))`，即**按注册位置破平局的稳定拓扑序**；
commons 是 `ArrayDeque` FIFO。共享的只有约 15 行排空循环，而**破平局规则恰恰是拓扑排序的产出
本身**——抽成公共工具等于让一方继承另一方的确定性保证。此外 ioc 另有 `findCycle` +
`describeCycle`（约 40 行，点名环上成员），commons 只有一句无成员的报文。**撤回，不动代码。**

**R5 的"两侧各一份"不成立。** 两侧不在同一条连线上：服务端读循环必须自己解帧、验掩码、
按 `frame.payload().length` 累计**字节**；mesh 那侧拿的是 JDK 客户端
`WebSocket.Listener.onText(CharSequence, boolean last)`——**分片边界已经给你了**，它只做拼接，
按 `data.length()` 累计**字符**。`TextMessageAssembler` 的 javadoc 本身就写着 "unlike the server
side, whose engine hands over a completed message"。所以两个 16MB 不是同一常量的副本，单位都不同。
**唯一可修的是注释**：`PeerConnector` 那句 "Mirrors the server side's inbound message limit
(`WebSocket.MAX_MESSAGE_SIZE`)" 有两处不实——① **该名字在全仓不存在**（真实常量是
`WebSocketReadLoop.MAX_MESSAGE_SIZE`，且是 `private`），注释指向一个幽灵 API；② "mirrors"
掩盖了单位差异。已改为陈述事实。

**R6 的初稿把可变/不可变当成了复制。** `Sql.addCondition` 复制 `conditions`/`args` 再
`new Sql(...)`；`Group.addCondition` 原地 `add` 后 `return this`。同名方法形状相似是**接口必然**
（两者都要能被 `.whereGroup(...)` 链式调用），不是冗余。真的重复只有 `addGroupedCondition`
的 7 行（建 Group、跑 builder、查空、委托），已抽为 `Sql.buildGroup(Consumer<Group>)` 静态
工厂——抽"构建"而非"自引用 helper"，是因为 Java 没有 self-type，泛型自引用会写成一个更难读的
函数式接口。

**R10 的初稿把结论说轻了，修法也过头了。** 原文说"逐字节相同，却绕开了 codec"，修法还建议
引入 `CloudHealthContributor`——过度设计：liveness 按定义就是常量，没有可聚合的依赖。先加断言
看它失败，实测：

```
/health/live must answer as JSON; got text/plain; charset=utf-8 with body {"status":"ok"}
```

`HttpResponse.send(int, String)` 的 javadoc 明写 "Content-Type defaults to text/plain"，
`sendJson` 才是 application/json。**同一模块的三个探针端点，两个说 JSON、一个说纯文本**，
而 `/health/ready` 自己还显式 `setHeader("Content-Type","application/json")`。修法就是
`ctx.sendJson(200, Map.of("status","ok"))`——body 逐字节不变，且顺带让这条路由也遵守可替换的
`JsonCodec`（`JsonCodecDefault` 是 AGENTS.md 明列的可 `.primary()` 角色）。回归测试
`HealthEndpointsTest.bothProbesAnswerAsJson` 同时钉住三个端点。

**R8 属实，但不能按初稿想的那样修。** 双重展开是真的（`HttpModule` 展开并解析后把
`groups` 原样交给 `WebSocketIndex`，后者在构造器里再展开一次），但它**不出 bug**，理由是
`WebSocketGroup.expand()` 重建的是 route、**复用 endpoint 实例**，而解析状态长在 endpoint 上
（`ResolvableEndpoint` 可变），所以索引拿到的副本已经是解析过的。审计初稿想改成 HTTP 那套
"传展开后的列表、groups 传 `List.of()`"——**不能改**：两条路径的索引内顺序本来就不同
（`RouteIndex` 块是显式路由在前再追加组展开，`WebSocketIndex` 构造器是组展开在前再追加显式
路由），统一写法会变更 WS 索引的顺序，进而变更同优先级路由的胜者与重复路径报错时点名的对象。
为省一次启动期的列表分配去动这个，不值。改为在 `HttpModule` 把**依赖的机制写成注释**：
若哪天 `expand()` 改为每个 route 新铸一个 endpoint，索引就会拿到未解析的副本——这正是原先
"看两条路径不一致、以为 WS 写错了"的陷阱。

**R7 不改，理由是抽出来更差。** 实测两侧 `resolve()` 各 14 行、体逐行同构（教科书式双检锁），
但**错误面不同**：`ResolvableHandler` 只有一个抛出点（`handle()`，带一段解释"为何会走到
这里"的注释），`ResolvableEndpoint` 有**两个**（`open()` 与 `subprotocols()`，报文也不同）。
要共享就得引入一个泛型 holder，两侧各自把 `handlerType()`/`endpointType()` 转发过去、把两个
抛出点留在本地——净省约 12 行，代价是 `http` 树里多出一个只为存两个字段的类型、四个方法
多一层间接。按 AGENTS.md「Keep concepts few」与「Prefer small explicit APIs」，双检锁本身
是**可内阅读者一眼认出的惯用法**，把它藏进 holder 反而更难读。**留档以免重复提出。**

### R2/R3 的实施要点（记录在案）

**R3 的一处判断修正**：初稿以为两处退避可以直接统一。实测发现 `attempt=0` 的语义
**相反**——`RetryerDefault` 返回 `baseMillis`（失败过一次要先等），mesh 返回 0
（没试过的 peer 立即拨号）。强行统一会改变一方行为，因此**第一跳的语义留在调用点**，
共享的只是曲线（`Backoff.millis` / `ceilingMillis`）。配置键同样各自独立。

**R3 的一处测试盲区**：`BackoffTest` 只测共享曲线时**通过了**，而 mesh 仍是无抖动
实现——因为两者是两条代码路径。补 `MeshDialBackoffJitterTest`（在 `event` 包，
`PeerConnector` 包私有）直接断言 dial 腿，**回退后立刻失败**。教训：
**测共享机制不等于测调用方**，尤其当"调用方已接入"本身就是待验证的事实。

**R4 说明**：两份失败策略不同是**有意的**（一个大声失败、一个降级继续），因此
"合并"指共享 Kahn 算法 + `Cycle` 判定，策略留在各自调用方。不取"删一份"的做法。

## 5. S4 — 完备性缺口（接线，不删能力）

| # | 缺口 | 依据 | 结论 |
|---|---|---|---|
| C1 | `HttpModule` 不为 HTTP 请求开作用域 | `grep Scope\|scoped\|Defer freeway-http/.../HttpModule.java` **零命中** | ⚠️ **已实施并回滚——见下。前提不成立** |
| C2 | `CloudEventBus` 无有序/异步发布 | `grep async\|Ordered` 在 `CloudEventBus.java` 零命中，而 `EventBus` 有 `publishAsync`/`publishOrdered` | 跨 JVM 顺序比进程内更值得保证（进程内 dispatch 已是一次方法调用）。把顺序机制**搬到真正需要它的平面**，而非从本地总线删除 | 中 |

### C1 已回滚：请求作用域在 HTTP 路径上没有对应的作用域单元

**实施到一半被测试推翻，代码已完整回滚**（`HttpServer.java` / `HttpModule.java` 经
`git checkout` 复原，http 461 例全绿）。

**实测发现的硬约束**：类路线的 handler **在启动期**就被解析——
`HttpModule.resolveLazy` → `c.create(handlerType)`，为的是 fail-fast。而启动期
**不存在任何请求作用域**，于是：

```
No open scope for type ScopedBean — wrap the call in
container.get(Scoping.class).within(() -> ...)
```

`HttpServer.withScoping(Scoping)` 能让作用域存在，**但改变不了 handler 在启动期被解析
这一事实**——加了它，场景依旧不可用，只是失败点从运行期挪到了启动期。

**这不是漏配，是三条既定设计合起来的结果**，且每一条都合理：

1. `HttpContext` **故意不暴露容器**——路由与 IoC 解耦；
2. handler 靠**构造注入**拿服务（见 `demo/rest-db` 的 `ListUsersHandler`）；
3. 类路线**启动期 fail-fast**，配置错误立刻暴露。

三者合起来：**handler 与请求作用域在结构上不兼容**，与 thread scope 是否接线无关。

**要真正支持需要以下之一，都是架构级决定**（`AGENTS.md`：改动落在会引发跨模块设计
讨论的地方）：

| 方案 | 代价 |
|---|---|
| handler 改为每请求构造 | 放弃启动期 fail-fast，与 `resolveLazy` 的意图直接冲突 |
| handler 改用 provider 闭包（lambda 内 `c.get(Bean.class)`） | 放弃类路线的类型安全，`Route.get(path, Class)` 这个主 API 基本作废 |
| 新增第三种作用域（request-scope，对象晚绑定） | `Scoping` 从两个语义变三个 |

**教训**：我把「`Scope.THREAD` 的场景成立」当成了前提，没有核实「请求作用域」这个概念
在 HTTP 请求路径上**有没有对应的作用域单元**。**场景成立 ≠ 有可用的作用域边界**——
中间这一步必须单独验证，而它恰恰是这次实施唯一真正的新增信息。

## 6. S5 — 本轮撤回的误判（记录在案）

### 6.0 方法参数注入：设计完成后判定不做（记录在案）

追问"`@Inject` 是否支持方法参数注入"时，实测确认**不支持且静默**：`InjectionResolver` 只有
`resolveArguments`（构造器参数）与 `injectFields`（字段）两个入口，`BeanPlan` 也只描述构造器
与属性，方法仅作为 getter/setter 参与属性模型。机制上无解——框架不做字节织造，无法拦截对
具体类的方法调用；`MethodInvocation.args()` 是**防御性副本**、advisor 改不了实参；`@Target`
里的 `PARAMETER` 也不能收窄（构造器参数正需要它）。遂先加 fail-loud 检查（`@Inject` 与
`@Symbol` 同形同盲区），再据请求给出完整设计方案（接口绑定已有代理、装配点置于 advice 链之前、
注解落在接口方法、启动期复用 `validateScopeBeforeResolution`、按 `Method` 缓存 plan）。

**该方案经讨论后判定不做**，理由有三条，其中第一条比"重复"更硬：

1. **它唯一的独有能力，框架已经用另一个机制给了。** 方法参数注入相对字段注入真正多出来的
   只有"每次调用解析"，而单例持有 THREAD 作用域**接口**字段时拿到的本就是每次调用按作用域
   解析的代理——`ServiceRuntime.java:108`「an interface proxy re-enters `realize()` on EVERY
   method call」与 `InjectionResolver.java:618` 的校验注释都写明了。且该代理**不持有目标**
   （首次调用才 realize），所以"不想持有引用"这一诉求本就满足。
2. **它会在框架自己的安全模型上制造不对称。** 字段注入走 `validateScopeBeforeResolution`
   ——单例不得持有 thread-scoped 具体类、不得持有 `@NotThreadSafe`，**启动期**报错，这是框架
   对"单例共享依赖"的核心防线。方法参数注入每次调用解析，这套校验要么每次重跑（热路径成本 +
   失败时机从启动期推迟到运行期），要么跳过（多一个绕过自身校验的注入点）。无论哪种，都变成
   **两个注入点、两套校验纪律**。
3. **它把注入的定义换掉了。** 字段/构造器注入是**类的属性**——接线声明一次，调用方一律不
   知情；方法参数注入把它变成**调用的属性**——每个调用方都得知道"这个参数是容器的"，写出来
   是 `foo.call(null)`，而这份知识在签名里**看不出来**（接口方法上它就是个普通参数）。依赖注入
   好用正是因为它是类自己的一次声明；方法参数是另一件事穿了注入的衣服。

**留下的产物是那条 fail-loud 检查**：把静默无效变成指名方法、指名注解、给出组合出路的报错。
全仓 2198 例**零误报**——此前无任何一处这样用过。反射台账已回写（见 §10）。

---

### 6.1 C2「把顺序机制搬到 mesh 平面」— ❌ 撤回（批次 4 最后一项，核实后不成立）

初稿判：`CloudEventBus` 对 `publishAsync`/`publishOrdered` 零命中而 `EventBus` 两者皆有，
**「跨 JVM 顺序比进程内更值得保证（进程内 dispatch 已是一次方法调用）」**，故应把顺序机制
搬过去。**核实后前提两半都错，不动代码。**

**第一半错：mesh 根本不经 broker。** `freeway-cloud` 里 `broker|kafka` 的命中**全部是
javadoc**，且都指向一个**尚不存在**的适配器（`EventOrigin.java:11`「the Kafka adapter reads
it from…」）。实际投递是**直连 peer**：`doPublish()` 遍历 `hub.connections()` → 对每个
`peer.send(json)` 写 WS。没有队列、没有分区、没有消费位点。所以"把进程内的顺序机制搬过来"
在运输层就没有对应物可搬。

**第二半错：两个平面做的是同一个承诺，作用域各自划定——这是一致，不是缺口。**
`EventBus.publishOrdered` 的 javadoc 自己就写着：

> Ordering is global **inside this JVM**: … but **the channel makes no promise past it**.

也就是说本地总线**已经拒绝承诺跨 JVM 顺序**，并且明说了。`CloudEventBus` 在**三处**声明了
mesh 不承诺顺序，且都给了理由（类 javadoc「at-most-once, best-effort volatile fabric …
the frame is not retried and not queued … per-key ordering is a durable-stream concern,
which is what an MQ plane (Kafka) is for」、`publish(topic,payload,subject)` 的
「the mesh itself imposes no ordering」、`subject` 参数的「carried on the wire and left to
consumers」）。这与 §6.2 表中 `ObjectStorage` 那三处属**同一形态**：能力门控 + 显式理由，
**不需整改**。

**并且这条保证在 mesh 上本就无法实现**，不是"懒得做"：at-most-once 且失败即丢（不重试、
不入队），意味着序列**天然有洞**；发布方还不止一个（多 peer 各自直连），没有全序可言。
要真给出跨 JVM 顺序，就得引入定序器/单写者或一个真正的流式平面——那正是作者指向 Kafka 的
理由。扩展点也已经留好了：`subject` 就是 CE 的 partition/ordering hint，**上线携带、交给
消费者**。

**还有一处形态差异让"搬过去"无处可搬**：mesh 的 `publish` **本身就是 fire-and-forget**
（不等待对端处理、无重试、无队列），即 mesh **只有 async 一种形态**。`publishAsync` 在这里
没有对应物可加；而 `publishOrdered` 要对"什么"排序？对一个不等待投递的发送，没有可比的
同步基线。

**教训**：这条与本轮其余撤回同族——看到两个类缺同一组方法，就假定是"该补未补"，**没先问
这个差异是不是被写下来的设计**。判据 §1.2 的 (B) 问的是"是否已有同类机制"，此处真正的
问题是"**是否已有同类决定**"，而答案在三个 javadoc 里。

### 6.2 上一轮以"零生产调用方"为删除理由的 4 条撤回

上一轮以"零生产调用方"为删除理由，撤回 4 条。撤回本身是结论的一部分——它说明
§1.2 那条判据的必要性。

| 撤回条目 | 原判 | 复核结果 |
|---|---|---|
| `EventStreams` / `EventBus.stream` | "157 行投机产物" | **撤回**。`EventStreams.java:31` 返回 **`java.util.concurrent.Flow.Publisher`**，基于 `SubmissionPublisher`（`:69`），接的是 **JDK 自带响应式词汇**，非自造。`offer` 保持非阻塞、慢消费者有 `onDrop`。开发基座该暴露这个 |
| `EventStats` | "与 `Metrics` 平行重复" | **撤回**。`EventStats.java:58` `Tally(LongAdder local, Metrics.Counter metric)` **已桥接** Metrics。本地计数供类型化 `stats()`，Metrics 供跨组件聚合——正是双写的正确姿势（SPI 可能是 `Noop`） |
| `advisor` AOP 整包 | "零调用方，删 4 个 public 类型" | **撤回**。零依赖下开发者想给自己的 bean 加计时/日志/校验，除代理无路可走，场景成立。真问题不同：`ServiceProxy.java:109` 的 `AdvisedHandler` 被**所有**接口绑定使用（99% 无 advice），每次调用白走一次链——是性能问题，不是冗余 |
| 三个 HTTP/2 帧类（`PriorityFrame`/`PushPromiseFrame`/`NotImplementedFrame`） | "解析后什么都不做" | **撤回**为冗余判定。RFC 9113 要求解析 `PRIORITY`、以 PROTOCOL_ERROR 应答 `PUSH_PROMISE`、对 UNKNOWN 帧回 `NOT_IMPLEMENTED`——协议强制，非投机。真问题见 S5 遗留项 |

| `cloud/storage` 三处"接口说谎" | "`presignedUrl` 无实现、`etag` 不是 etag、`ObjectMetadata` 被丢弃——开发基座有说谎的接口比没有更糟" | **撤回**。三处**全部由作者显式记录了理由**：`ObjectStorage.java:28-32`「when the backend supports it… empty by default; object-store backends (S3) provide it」；`ObjectEntry.java:11-13`「content digest **when the backend stores it**… not a content hash」；`ObjectStorageDefault.java:69-75`「deliberately ignored here rather than **half-honored**」。属能力门控契约，**不需整改** |

**关于本条撤回的判据独立性**（本轮方法论修正）：初稿的推理链是
「看到无实现/非 etag/被丢弃（代码）→ 判定说谎 → **读 javadoc** → 发现作者写了理由 → 撤回」，
即**撤回结论依赖了文档文本**。这违反本文 §1.2 的证据分级。

**纯代码即可判定**：`presignedUrl` 是 `default` 方法返回 `Optional.empty()`，
实现类不覆盖——这是 **SPI 可选能力的标准形态**，不构成缺陷。
`etag` 在 record 上有明确类型与生成规则（`size + "-" + mtime`，实现处注释自承
"derived tag, not a content hash"）。`ObjectMetadata` 字段在实现入口被显式忽略。
三者合起来是"接口宽、实现窄"的常规 SPI 分层，**代码层面即无缺陷**——
javadoc 只是把已成立的事实写了出来。

**教训**：判"是否说谎"时，**先看代码形态**（default 方法？record 契约？实现是否覆盖？），
再读文档找作者的意图说明。若代码形态本身已构成合理模式，文档是佐证而非判据。
本文其余各条已按此复核。

**S5 遗留**（不属上述条目，另记）：H2 帧的 `writeTo` 从未被调用，
`DataFrame.writeTo`（`DataFrame.java:48`）漏帧头/pad-length/padding、
`HeadersFrame.writeTo`（`HeadersFrame.java:55-58`）漏 pad-length/padding 与其 `:34-41` 解析的
PRIORITY 字段。**未使用的代码写错了**，是未来调用者会继承的陷阱——要么修，要么删。

## 7. 有意保留（不改）

| 保留项 | 依据 |
|---|---|
| `advisor` AOP 整包 | 场景成立（§6）。可优化 `AdvisedHandler` 快路径，不删 |
| `Scope.THREAD` + `Scoping` + `ScopedCache` | 请求级单例是基座标配；`ServiceRuntime.java:121` 的 fail-loud 是正确设计。补接线（C1），不删 |
| `cloud/storage/` 整包 | 对象存储是真实云需求，接口作为 SPI 有价值。**本轮曾误判为"接口说谎"，已撤回（见 §6 表）**——三处非对称实现全部由作者显式记录了理由（能力门控），是"产物包含理由"的正面样本 |
| `PlantUml*` 定制层 | 自定义渲染是合理需求。~~**须修**~~ → **批次 2 已修**：`Graph.java` 两处 `catch (Exception ignored)` 已改为 fail-loud，回归测试 `PlantUmlDisplayFailureTest` |
| `EventStreams`/`publishAsync`/`publishOrdered`/`EventStats`/`DeadEvent`/`Stoppable` | 见 §6。补一个锚点测试即可 |
| `PathJoiner`（19 行 / 4 调用方） | **保留**，且初稿"真冗余"的判断也不对：`normalize()` **已经**委托 `PathPattern.normalizePath`，多出的只是 join 专有的一条约定（根路径 `"/"` 归一为空串，否则拼接会产生 `//`）加一次拼接。真正的逻辑只有一行，为省它去改 4 个调用点不值 |
| `FlowEventBus` / `NamedTaskHandler` | 场景弱但无害。**降级**为"补调用方或测试"，不进本轮 |

## 8. 执行批次

按"代价低、收益高、风险小"排序。**每批次独立可发布、独立跑 `mvn -pl <m> -am test`**。

### 批次 1 — 静默正确性（不改形状，编译错误即迁移）
1. S1-1 `@PostConstruct` 双跑 + 回归测试
2. S1-2 `Sql.setColumn` 内联/展开 + 回归测试
3. S2-3 `Schema.ensure` 索引计数 + 回归测试
4. S2-2 关闭/`get` 并发活代理 + 并发回归测试（**实测低概率**，可与前三条解耦发布）

*批次 1 集中在 4 个文件、0 个 API 形状变更。S2-2 因实测为 20000 次 1 中，
优先级低于 S1-1/S1-2（后者必现）。*

### 批次 2 — 产物说真话（1 处改代码 + 其余改文档）
5. S2-1 统一 body 限制到活实现，改 `readBody` 为薄委托；改正 `HttpEngine` 契约与
   **`AGENTS.md:227`** + 跨引擎 413 回归测试
6. S2-4 改正 `AppRuntime` 跨线程语义描述（**仅改注释**）
7. S5 遗留：修或删 H2 帧的 `writeTo`
8. §7 `PlantUml*` 两处 `catch (Exception ignored)` 改为 fail-loud

### 批次 3 — 机制归一（真冗余，先高后低）
10. R2 mesh hello 状态机抽 `MeshFrameDecoder`（两份 onText 只提供 send/reply 行为）
11. R3 共享退避实现，**给 mesh 补上 jitter**（`RetryerDefault` 已有，先搬后加）
12. R5 WS 重组 / 16MB 常量
13. R6 `Sql.Group` 抽共享静态 helper；顺带塌 8 个 `requireXxx` 为一个 helper
14. R4 Kahn 算法共享（策略各留）
15. R7+R8+`PathJoiner` 低优先合并
16. R9 图规范化去重
17. R10 `/health/live` 走 `CloudHealthContributor`

### 6.3 测试套件冗余审计 — 覆盖面不冗余，辅助方法合并净收益约等于零

**问的是"测试用例有没有冗余重复"。结论：没有。**

| 度量 | 结果 |
|---|---|
| 测试方法总数 | **1655** |
| 跨类重名 | 5 个，且**全是不同被测类型各自的同名契约**（5 份 `closeIsIdempotent` 分属 AppConfig / FreewayApp / ScopedCache / Container / EventBus） |
| 真实的重复场景 | **0** |

**辅助方法层面**确有副本：脚本按"方法体归一化后取哈希"找出 34 组跨文件同形方法
（`freePort` × 9、`rootMessage` × 5、`readFully`/`waitForStatus200`、`awaitMesh`/`cleanup` 等），
分布在 http/db/cloud/boot/ioc 五个模块。**但这个 34 是高估值，逐条核实后大幅缩水**——
自动化检测在此**误报率高**，理由与本轮其余撤回同源（**第六次**"形状相同 ≠ 同一事物"）：

- `uniqueDb` × 6 **不是重复**：六份硬编码前缀各不相同（`freeway_orm_` / `freeway_mapping_` /
  `fw_schema_` / `freeway_batch_` / `freeway_row_` / `user_demo_`），是**刻意隔离**——共享 DB 实例下
  互不碰撞，且诊断能指出来源类。哈希把字符串字面量一起归一化了，所以它们"同形"。
- `db/schema` 的 `query` / `transaction` × 3 **根本不存在**：这些文件里没有同名方法，只是散文里
  出现"transaction"一词。检测器把任意同形的方法体判成重复。
- `freePort` 的 9 份签名不一（`throws Exception` vs `throws IOException`），另有一个
  `freePortQuiet` 包装版——**不是静默降级**（包成 `IllegalStateException` 抛出），无缺陷。

**最终只合并了两簇**（都逐字节验证过，且都存在真实漂默风险）：

| 簇 | 内容 | 净行数 |
|---|---|---|
| http `TestRawHttp` | `readFully` / `readFullyOrEof` / `waitForStatus200` / `readHttpResponse`，3 文件 4 份 → 1 | **−14** |
| cloud `CloudMeshTest` | `cleanup()` + `awaitMesh()`，2 文件 → 抽象基类 | **−3** |

**两簇净收益都约等于零**，这是本次审计最该记下的数：删掉 219 行副本，就要补 202 行共享类
（其中 60 余行是 javadoc）。**行数上打平，价值全在"只有一处"**——而这个价值只在两处是真的：

1. **HTTP/2 帧头解析**（位掩码、stream-id mask、GOAWAY 判定）：修一处而另一处静默保留旧行为，
   测试还在绿。
2. **mesh 拆卸清系统属性**：漏清一个就污染同 JVM 后续所有测试，而**失败会出现在没人看的类里**。

其余约 25 组**有意不动**：为单个方法建一个类不划算，且 AGENTS.md「Keep concepts few」与
「行数不是合并的理由」都不支持。**跨模块共享（`freePort` 的 9 份横跨 http/cloud）需要 test-jar
依赖，属构建形状变更**，未擅自决定。

**方法论补记（第二次）**：本轮先用脚本给出"34 组重复"的量化结论，再逐条核实，发现**误报率
高到结论不可用**。自动化相似度检测在"什么算同一件事"这个问题上**没有发言权**——它能把
"刻意不同"报成"相同"（`uniqueDb`），也能把"不存在"报成"存在"（`query`/`transaction`）。
数字必须回到源码逐条读。

### 批次 4 — 完备性接线18. ~~C1 `HttpModule` 每请求开作用域~~ → **已回滚**，前提不成立（见 §5）
18.5 **S1-4 并行 join 记账竞态** — ✅ 已修（见 §2.4；本轮在全量跑中偶发暴露，非本轮改动所致）
19. ~~C2 顺序机制搬到 mesh 平面~~ → **已撤回**，前提不成立（见 §6.1）

### 批次 5 — 文档对齐 — ✅ 已完成

20. **`docs/freeway-config.md` 的 MySQL DDL 语义错位**。原表把
    `freeway.db.migration.enabled` 写为"生产必须为 `true`"，未提方言依赖。
    核实后发现两个守卫的**判据完全不同**，此前被混为一谈：

    | 路径 | 判据 | MySQL 上的结果 |
    |---|---|---|
    | `MigrationRunner` | `!supportsTransactionalDdl()` **且**迁移含 DDL → 抛 | **含 DDL 的迁移一律不可用** |
    | `Schema.ensure` | `db.inTransaction()` **且** `!supportsTransactionalDdl()` → 抛 | **正常运行**（仅禁止在用户事务内跑 DDL） |

    现两行都写明这一区分，并各自给出该方言下的可行做法
    （migration：拆分幂等 DDL 或改用 `schema.mode=auto`）。
21. **`AGENTS.md` 的 Adapters 条款**。它把 `readBody` 点名为 413 的共享实现，
    而 S2-1 修好后这句话**第一次成立**——现补上委派关系与"同一份代码而非两套碰巧相符
    的循环"，使条款与实现对应。
22. **`DEVELOPER-GUIDE`**。`Container` 表格行补上 `create` 的生命周期语义；
    Scopes 章节新增两节：
    - **「Where a `Scope.THREAD` service can be used」**——把 C1 实测到的约束写成
      使用者会读到的形态：类路线 handler 在**启动期**创建，那里没有请求作用域，
      因此构造参数里带 thread-scoped 服务会**启动失败**并给出那条
      `No open scope for type X` 报文，并说明那是拒绝按设计生效。
    - **「Service Lifecycle」**——一张四行表说明 `@PostConstruct` / `@PreDestroy`
      在各条产生路径下是否成对，重点解释 `create` 为何是唯一不成对的那一行
      （它是调用方拥有的实例，容器不会在关停时遍历它）。

**`ConfigDocsConsistencyTest` 通过**——boot 模块已有一条测试校验配置文档与
`ConfigKeys` 的一致性，本次改动未破坏它。全仓 2186 例全绿。

## 9. 复核命令

> **判据优先**：以下命令验证**行为判据**，不验证行号。行号仅在需要跳转时手工定位。

### 9.1 实测条目（探针应可重跑，输出与 §2/§3 所载一致）

```sh
mvn -o -q -pl freeway-ioc  -am compile -DskipTests
mvn -o -q -pl freeway-db  -am compile -DskipTests
CP="freeway-ioc/target/classes:freeway-db/target/classes:freeway-commons/target/classes"
SLF=~/.m2/repository/org/slf4j/slf4j-api/2.0.18/slf4j-api-2.0.18.jar

# S1-1：@PostConstruct 计数（期望 2 → 修后 1）
#   探针见 §2 S1-1，核心是 to(x -> x.create(Impl.class)) + @PostConstruct 自增
# S1-2：setColumn 绑定类型（期望 "Sql" → 修后内联、args=0）
#   探针见 §2 S1-2，核心是 sql.args()[0].getClass()
# S2-2：并发泄漏（期望 20000 次中 0 → 修后 0；当前 1）
#   探针：一个线程 get、主线程并发 close，循环计数
```

修复后应把上述三个探针**固化为回归测试**，随代码入库——
探针留在文档里会随 SNAPSHOT 漂移，进测试套件才不会。

### 9.2 代码事实条目（grep 即可复核，不读注释）

```sh
# S2-1：readBody 零调用方（drainUnreadBody 为同名不同义，非调用）
grep -rn "readBody" --include="*.java" .
grep -n "readBody" AGENTS.md            # 规则文件把它写成共享契约

# S2-3：executed++ 在三个 drift 分支中的分布（期望 TABLE/COLUMN 各 1、INDEX 0）
grep -n "executed++" freeway-db/src/main/java/com/jujin/freeway/db/schema/Schema.java

# S2-4：start/close 同步性（两者皆 synchronized → 跨线程阻塞）
grep -n "synchronized" freeway-boot/src/main/java/com/jujin/freeway/boot/internal/AppRuntimeDefault.java

# C1：HttpModule 是否开作用域（当前应为空）
grep -n "Scope\|scoped\|Defer" freeway-http/src/main/java/com/jujin/freeway/http/HttpModule.java

# §6 撤回：EventStreams 返回 JDK Flow.Publisher（而非自造流）
grep -n "Flow.Publisher\|SubmissionPublisher" \
  freeway-ioc/src/main/java/com/jujin/freeway/ioc/event/EventStreams.java

# §6 撤回：cloud/storage 属"接口宽、实现窄"的 SPI 形态（default 方法未被覆盖）
grep -n "default Optional<URL> presignedUrl" \
  freeway-cloud/src/main/java/com/jujin/freeway/cloud/storage/ObjectStorage.java
```

## 10. 见也

- `docs/audit-philosophy-modernity-1.5.2.md` — 理念一致性与现代性审计（本文不重复）
- `docs/ARCHITECTURE.md` — 模块边界、配置级联机制
- `docs/freeway-reflection.md` — 反射点台账（批次 1 若改到 `BeanPlan`/`MethodHandleUtils` 需回写）。
  **后续追加**：为方法参数注入的 fail-loud 检查新增了 `ioc.internal.InjectionResolver` 一行——
  `getDeclaredMethods()` + `getParameterAnnotations()` 的 `ClassValue` 站点（该表此前**完全没有**
  `InjectionResolver` 的行，尽管它一直在做字段属性扫描）。该特性本身经讨论后**不做**（结论见
  §6），但检查留下，因为它把静默无效变成指名报错。
- `AGENTS.md` — "Regressions to Watch"（S2-1 直接修订其中一条）
