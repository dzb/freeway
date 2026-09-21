# Freeway 配置参考

> 所有配置项采用点号分隔的层级键，统一在 `freeway.*` 命名空间下。
> 配置来源优先级（低 → 高）：`application.properties` → `application.json` → `application-{profile}.properties` → `application-{profile}.json` → **工作目录/`freeway.config.file` 覆盖文件**（同名同序，文件系统整体高于类路径基线，可热重载） → **模块贡献的源**（如 cloud secret store，`order()=15`） → 环境变量（`FREEWAY_*`，`order()=10`） → **JVM 系统属性**（主类启动前 `-Dkey=value`，键原样，`order()=5`） → CLI 参数（`--key=value`，`order()=0`）。
> 优先级由各来源声明的 `SymbolProvider.order()` 决定（升序查找、首个命中即胜），与模块安装顺序无关。
> 详见 [ARCHITECTURE.md](ARCHITECTURE.md) 的配置级联章节。

---

## 配置分类规则

每个键的**档**写在下面各表里。分档不是口味问题，而是四个问题按顺序问下来的结果——**同一把尺子量所有键**，新增键时照同一顺序回答即可：

| # | 判据 | 落到哪一档 |
|---|------|-----------|
| 1 | 没有合理默认、缺失即启动失败？ | **必填**（无条件）／**必填·条件**（某个特性开启时才必填） |
| 2 | 有默认，但默认只在开发/本机成立？ | **决策** —— 部署时按环境改 |
| 3 | 默认就是推荐姿态，只在关掉某特性时才写？ | **姿态** —— `.enabled` 家族与 presence 主键 |
| 4 | 值是数字/时长/大小/路径，默认即框架推荐值？ | **调优** —— 不动 |

另外两类**不是"决定"**，别和上面混：

- **声明**（`freeway.cloud.{secret,storage,discovery,registry}.type`）：只表达"我期望用哪个后端"，**不选择实现**（选择＝`.primary()` 绑定、`@Local` 标记、装哪个适配器模块）；仍用本地实现而声明非空时启动告警一次。
- **机制**（`freeway.config.file`、`freeway.env.prefix`、`freeway.cloud.secret.file/.keys`）：配置的是配置系统本身，写进任何配置文件都无效。前两个可来自 `-D` 或 `FREEWAY_CONFIG_FILE`/`FREEWAY_ENV_PREFIX`；后两个**只认 `-D`**——它们经符号解析链读取，走一遍级联会递归。

**形态后缀**说明这个值是怎么来的（它决定能不能被"隐藏"）：

| 后缀 | 含义 | 例子 |
|------|------|------|
| `·auto` | 值必须**推导**，写不出来 | `log.file=auto`（路径含 runtime 的 `app.name`）、`registry.service-scheme=auto`（跟随 HTTP 服务器是否启用 TLS）、`registry.service-host=auto`（POD_IP → 首个可路由本地地址）、`registry.shutdown-drain=auto`（由注册表后端回答） |
| `·presence` | 主键一出现，整簇子键才谈得上 | `ssl.key-store`（路径即启用 HTTPS）、`event.peers`、`rpc.tls.key-store` |
| `·哨兵` | 空/`0` = 交给平台、JDK 或运行时 | `dialect=""`（从 JDBC URL 推断）、`registry.service-port`（空＝实际监听端口）、`max-connections=0`（OS 默认） |
| `·聚合闸` | 一个键统管一簇调优键 | `rpc.resilience=auto\|off`（`off` 时忽略全部 `retry.*`/`circuit-breaker.*`/`rate-limit.*`） |

**你不必读的档**：`姿态` / `调优` / `声明` / `机制` 四档的默认值就是推荐值，只有 `必填` 与 `决策` 需要按部署回答。一个不涉及 HTTPS、不面向浏览器的服务只需要其中 2 个：

| 模块 | 必须决定的键 | 说明 |
|------|-------------|------|
| **DB** | `freeway.db.url`、`freeway.db.username` | 无默认值，启动时必检；密码走 `FREEWAY_DB_PASSWORD` |
| **HTTP** | `freeway.http.server.host` / `.port` | 默认 `127.0.0.1:8080`；容器里通常改成 `0.0.0.0` |
| **HTTP** | `freeway.http.ssl.key-store` + `-password` | 生产启用 HTTPS 时（keystore 路径即启用；不配即明文） |
| **HTTP** | `freeway.http.cors.allowed-origins` | 默认 `*` 是开发姿态，部署时写真实域名 |
| **Cloud** | `freeway.cloud.event.peers` + `.token` | 启用事件网格时两者都要（peers 非空即开关；token 全节点一致） |
| **Cloud** | `freeway.cloud.rpc.tls.key-store` + `-password` | 出站 RPC 走 mTLS 时（路径即启用） |
| **Cloud** | `freeway.cloud.registry.service-host` | `auto` 在容器里通常够用；多网卡主机需点名 |
| **Cloud** | `freeway.cloud.secret.file` / `.keys` | 密钥后端自身配置，**仅 `-D` 可读**（环境变量不生效，见 §五·密钥） |

**为什么调优档不需要"总开关"**：每簇已经有一个键在管它，再加一个只是换种说法说"关"——`rpc.resilience` 管九个弹性键，`event.enabled` presence 管四个 `event.*-ms` 传输超时（网格不存在时它们无从谈起），`ssl.enabled` presence 管整簇 TLS，调优键自己写着"用平台默认"（`0` ＝ OS/JDK 默认，空 ＝ JDK 默认）。

**为什么不把调优键都改成 `auto`**：`auto` 只在**值必须推导**时才成立（带 `·auto` 后缀的共 6 个键：日志路径/格式/颜色、注册 scheme/host、停机 drain）。给一个常量默认值加 `auto`，得到的只是同一规则的第二种说法——多一个分支、多一套文档、多一处测试，换不来信息。**日志**是范本：`log.file=auto` 让 `logs/{app.name}.log` + 双滚动 + GZIP + 30 天保留整簇隐身，而 `max-size` 这类常量键保持可写、只是不进样例。

### 配置文件怎么组织

**默认一个文件**：`application.properties`（或 `.json`）作基线 + `application-{profile}.*` 作环境轴。理由：需要人回答的只有上表那几个键，生产样例一共也只生效 22 个；profile 已经是"按环境分区"的正交轴。

按模块拆分是**可选约定**，只在"多团队/多 ConfigMap 各自拥有配置"时才有价值——机制上不需要改任何代码，`freeway.config.file` 已支持多文件（逗号分隔、顺序即优先级、全部热重载）。拆分时三条纪律：

1. 文件与命名空间 1:1：`config/http.properties` 只放 `freeway.http.*`；
2. **一个键只有一个家**——同一键出现在两个覆盖文件里会告警并点名两个文件（后者胜），但不要依赖它；
3. 顺序显式写在 `FREEWAY_CONFIG_FILE` / `-Dfreeway.config.file` 里，不靠文件名猜。

两条边界：`freeway.config.file` 列出的额外文件**不派生 profile 变体**（模块 × profile 会让文件数翻倍，profile 增量仍应回到 `application-{profile}.*`）；框架**不自动加载**每模块的默认文件——默认值只有一个来源，就是各模块 `*ConfigKeys` 里的常量。

---

## 模块总览

| 模块 | 命名空间 | 说明 |
|------|----------|------|
| **Boot** | `freeway.profile`, `freeway.config.file`, `freeway.env.prefix` | 运行时启动与配置级联 |
| **Commons** | `freeway.log.*`, `freeway.env.prefix` | 日志系统 |
| **HTTP** | `freeway.http.*` | Web 服务器、路由、SSL |
| **DB** | `freeway.db.*` | 数据库连接、池、Schema、迁移 |
| **Cloud** | `freeway.cloud.*` | 云原生 — 发现、RPC、弹性、事件、存储、密钥 |
| **Flow** | 无外部配置 | 工作流引擎，纯编程式配置 |
| **IoC** | 无外部配置 | 容器、绑定、作用域，纯编程式 |

---

## 一、Boot — 运行时启动

### 配置项

| 键 | 档 | 类型 | 默认值 | 必填 | 说明 |
|----|------|------|--------|------|------|
| `freeway.profile` | 决策 | String | *(无)* | 否 | 激活的 Profile，支持逗号分隔多个。不设则不加载任何 profile 变体文件。开发用 `dev`，生产用 `prod` |
| `freeway.config.file` | 机制 | String | *(空)* | 否 | 额外配置文件路径，多个逗号分隔。参与文件级热重载 |
| `freeway.env.prefix` | 机制 | String | `FREEWAY_` | 否 | 环境变量前缀；自定义前缀时 `APP_SERVER_PORT` → `server.port`（透传） |

`freeway.config.file` 与 `freeway.env.prefix` 是 **bootstrap-only 键**：
它们配置的就是配置系统本身，因此只能来自 `-D<键>` 或 `FREEWAY_<键>`
（如 `FREEWAY_ENV_PREFIX`、`FREEWAY_CONFIG_FILE`）。
写进 `application.properties`/`application.json`（类路径或工作目录）无效，作为
CLI 参数（`--freeway.config.file=...`）同样无效——启动时会打 WARN 点名来源文件或
"命令行参数"，不再静默忽略。`FREEWAY_` 前缀对这两个键固定不变（否则就要用被
`env.prefix` 配置的映射去读 `env.prefix`）；`freeway.profile` 不是 bootstrap
键，走正常级联。

激活 profile 的只有**基础层**：类路径与工作目录的 `application.properties`/
`application.json`、映射后的环境变量（`FREEWAY_PROFILE`）、CLI 参数
（`--profile=dev`）。profile 变体文件里重写 `freeway.profile` 会被剥离，避免
`profiles()` 与解析值互相矛盾；另外两个渠道只写入普通配置值、**不激活 profile**：
`-Dfreeway.profile=dev`（JVM 系统属性）与 `freeway.config.file` 附加文件。

### CLI 快捷规则

- 不含点号的键自动加 `freeway.` 前缀：`--profile=dev` 等价于 `--freeway.profile=dev`
- 含点号的键透传不变：`--app.name=foo` → `app.name=foo`

### 示例

```json
{
  "freeway": {
    "profile": "dev",
    "config": {
      "file": "/etc/freeway/extra.properties"
    },
    "env": {
      "prefix": "FREEWAY_"
    }
  }
}
```

---

## 二、Commons — 日志系统

### 配置文件的两个家

日志键可以住在两个地方（`-D` 系统属性与环境变量都高于两个文件；专用文件更具体、优先于 app 文件；本节全部键只在 `freeway.log.*` 命名空间内生效）：

1. **专用文件** `freeway-logging.properties`（classpath 根）——日志专属声明，**优先生效**。旧名 `freeway-log.properties` **不再被读取**：若它仍留在 classpath 根，启动会打一行 stderr 提示改名（配置不会被采纳，但也不会静默失效）
2. **应用主配置** `application.properties` / `application.json`（classpath 根 + 工作目录 + 激活 profile 的变体，后读的文件赢）——只取 `freeway.log.*` 键

注意三条边界：

- **四个键住不进文件**：`freeway.log.color`、`freeway.log.mdc`、`freeway.log.mdc.priority`、`freeway.log.caller-info` 在**类加载期**读取（早于文件解析），只认 `-D` 与环境变量，写进上面两个家都静默无效。
- **per-logger 级别**（`<logger>.level`）只支持专用文件——在应用主配置里它与普通点号键无法区分（会把任意应用键误认成 logger）。
- 应用文件侧走主级联的 **classpath 基线**（含 `-D`/env/类路径文件声明的 profile 选择）；工作目录文件声明与热重载不参与日志侧。

另有三个**进程级键**（不是 `freeway.*`，框架同样读取）：

| 键 | 档 | 类型 | 默认值 | 说明 |
|----|------|------|--------|------|
| `app.name`（JVM `-D`） | 机制 | String | `freeway` | 默认日志文件名 `logs/{app.name}.log`。与 `freeway.app.name`（cloud 服务名回退）是两回事 |
| `slf4j.provider`（JVM `-D`） | 机制 | String | 探测后写入 | SLF4J provider 选择（logback > log4j > simple）；已显式设置则不覆盖 |
| `NO_COLOR`（环境变量） | 机制 | 存在性 | 未设 | 存在即关闭 ANSI 颜色（遵循 no-color.org） |

#### 全局日志

| 键 | 档 | 类型 | 默认值 | 必填 | 说明 |
|----|------|------|--------|------|------|
| `freeway.log.level` | 决策 | String | `INFO` | 否 | 全局日志级别。SLF4J 名（TRACE/DEBUG/INFO/WARN/OFF）或 JUL 名（FINEST/FINE/INFO/WARNING/SEVERE） |
| `freeway.log.format` | 调优·auto | String | `auto` | 否 | 格式化模式：`auto`（Freeway formatter + ANSI）或 `simple`（JUL SimpleFormatter） |
| `freeway.log.color` | 调优·auto·-D | String | `auto` | 否 | 颜色模式：`auto`（跟随 format）、`always`（强制 ANSI）、`never`（强制无色）。遵循 `NO_COLOR` 规范 |
| `freeway.log.caller-info` | 调优·-D | Boolean | `true` | 否 | 每条日志解析 source class/method。日志量大时可设为 `false` 提升吞吐 |
| `freeway.log.mdc` | 调优·-D | Boolean | `true` | 否 | 日志输出是否包含 MDC 上下文。MDC（Mapped Diagnostic Context）是每线程级的上下文键值对，用于分布式链路追踪、请求关联等场景 |
| `freeway.log.mdc.priority` | 调优·-D | String | 未设（全部按字母序） | 否 | MDC 键的显示优先级顺序（逗号分隔），未列出的键按字母序排列。**框架不预设任何键名**——要突出的键（如 `traceId`/`requestId`）由应用自己声明 |

#### 控制台输出

| 键 | 档 | 类型 | 默认值 | 必填 | 说明 |
|----|------|------|--------|------|------|
| `freeway.log.console.enabled` | 姿态 | Boolean | `true` | 否 | 是否启用控制台处理器 |
| `freeway.log.console.level` | 调优 | String | *(继承 root)* | 否 | 控制台处理器级别。空则继承全局级别 |

#### 默认文件日志

| 键 | 档 | 类型 | 默认值 | 必填 | 说明 |
|----|------|------|--------|------|------|
| `freeway.log.file` | 调优·auto | String | `auto` | 否 | 文件日志模式：`auto`（全托管 `logs/{app.name}.log`）或 `off`（禁用） |
| `freeway.log.file.max-size` | 调优 | Long | `104857600` (100 MB) | 否 | 大小滚动阈值（字节） |
| `freeway.log.file.max-history` | 调优 | Integer | `30` | 否 | 归档保留天数 |
| `freeway.log.file.compress` | 调优 | Boolean | `true` | 否 | 归档后是否 GZIP 压缩 |
| `freeway.log.file.flush-interval` | 调优 | Long | `250` | 否 | 后台刷盘间隔（毫秒）。`0` = 每条立即刷盘（最保险，吞吐低） |

#### 多文件日志

| 键 | 档 | 类型 | 默认值 | 必填 | 说明 |
|----|------|------|--------|------|------|
| `freeway.log.files` | 姿态·presence | String | *(空)* | 否 | 逗号分隔的命名文件列表（如 `biz,audit`） |
| `freeway.log.file.<name>.path` | 必填·条件 | String | *(必填)* | 是 | 命名文件的路径（命名文件场景下必填） |
| `freeway.log.file.<name>.logger` | 决策 | String | *(root)* | 否 | 绑定到命名文件的 Logger 名 |
| `freeway.log.file.<name>.level` | 调优 | String | *(继承父)* | 否 | 命名文件的日志级别 |
| `freeway.log.file.<name>.max-size` | 调优 | Long | `104857600` | 否 | 命名文件大小阈值 |
| `freeway.log.file.<name>.max-history` | 调优 | Integer | `30` | 否 | 命名文件归档保留天数 |
| `freeway.log.file.<name>.compress` | 调优 | Boolean | `true` | 否 | 命名文件是否 GZIP 压缩 |
| `freeway.log.file.<name>.flush-interval` | 调优 | Long | `250` | 否 | 命名文件刷盘间隔 |

#### 按包/类粒度级别

| 键 | 档 | 类型 | 默认值 | 必填 | 说明 |
|----|------|------|--------|------|------|
| `<logger-name>.level` | 调优 | String | *(继承父)* | 否 | 按包/类设置日志级别，如 `com.zaxxer.hikari.level=WARNING` |

### 示例

```json
{
  "freeway": {
    "log": {
      "level": "INFO",
      "console": {
        "enabled": true,
        "level": "INFO"
      },
      "file": "auto",
      "file.max-size": 104857600,
      "file.max-history": 30,
      "file.compress": true,
      "file.flush-interval": 250,
      "files": "biz,audit"
    }
  }
}
```

> `color` / `caller-info` / `mdc` / `mdc.priority` 不在此例中：它们只认 `-D` 与环境变量
> （如 `-Dfreeway.log.mdc.priority=code,traceId`），写进文件静默无效。`file.max-size`
> 等四项属 `freeway.log.file.*`，写成一层的 `"max-size"` 会变成 `freeway.log.max-size`
> 这个不存在的键。

---

## 三、HTTP — Web 服务器

### 配置项

**分档**（规则见开头"配置分类规则"）：决策 = `server.host`/`server.port`、
`server.shutdown-grace`、`cors.allowed-origins`，以及启用 HTTPS/mTLS 时的
`ssl.key-store` + `-password`、`ssl.trust-store` + `-password`；姿态 = `ssl.key-store`
（presence）与各特性 `.enabled`（`compression`、`access-log`、`cors`、`health`）；
其余为**调优**——默认即推荐值，`0` 表示 OS/JDK 默认或无限制，空表示 JDK 默认。

#### 服务器

| 键 | 档 | 类型 | 默认值 | 必填 | 说明 |
|----|------|------|--------|------|------|
| `freeway.http.server.host` | 决策 | String | `127.0.0.1` | 否 | 绑定地址。开发 `127.0.0.1`，生产 `0.0.0.0`（由 Nginx 等反向代理转发） |
| `freeway.http.server.port` | 决策 | Integer | `8080` | 否 | 监听端口（`0` = 系统分配） |
| `freeway.http.server.backlog` | 调优 | Integer | `0` | 否 | Accept 队列大小（`0` = 系统默认） |
| `freeway.http.server.shutdown-grace` | 决策 | Duration | `2s` | 否 | 关闭时等待 in-flight 请求的时间。开发 `2s`，生产 `30s`（滚动部署） |
| `freeway.http.server.read-timeout` | 调优 | Duration | `30s` | 否 | Socket 读空闲超时（`0` = 禁用） |
| `freeway.http.server.write-timeout` | 调优 | Duration | `30s` | 否 | 单次写操作超时（`0` = 禁用） |
| `freeway.http.server.max-connections` | 调优 | Integer | `0` | 否 | 最大并发连接数（`0` = 不限） |
| `freeway.http.server.receive-buffer-size` | 调优 | Integer | `0` | 否 | SO_RCVBUF（`0` = OS 默认） |
| `freeway.http.server.send-buffer-size` | 调优 | Integer | `0` | 否 | SO_SNDBUF（`0` = OS 默认） |

#### 压缩

| 键 | 档 | 类型 | 默认值 | 必填 | 说明 |
|----|------|------|--------|------|------|
| `freeway.http.compression.enabled` | 姿态 | Boolean | `true` | 否 | 启用 gzip 响应压缩 |
| `freeway.http.compression.min-size` | 调优 | Integer | `256` | 否 | 压缩最小响应体（字节） |

#### 访问日志

| 键 | 档 | 类型 | 默认值 | 必填 | 说明 |
|----|------|------|--------|------|------|
| `freeway.http.access-log.enabled` | 姿态 | Boolean | `false` | 否 | 启用文本访问日志（stdout 输出 method path status elapsed-ms） |

#### CORS

| 键 | 档 | 类型 | 默认值 | 必填 | 说明 |
|----|------|------|--------|------|------|
| `freeway.http.cors.enabled` | 姿态 | Boolean | `true` | 否 | 启用 CORS 过滤 |
| `freeway.http.cors.allowed-origins` | 决策 | List(String) | `*` | 否 | 允许的源（逗号分隔）。生产环境必须指定具体域名；`*` 与 `allow-credentials=true` 冲突，启动失败 |
| `freeway.http.cors.allowed-methods` | 调优 | List(String) | `GET, POST, PUT, DELETE, PATCH, OPTIONS` | 否 | 允许的方法 |
| `freeway.http.cors.allowed-headers` | 调优 | List(String) | `Content-Type, Authorization` | 否 | 允许的请求头 |
| `freeway.http.cors.exposed-headers` | 调优 | List(String) | *(空)* | 否 | 暴露的响应头 |
| `freeway.http.cors.max-age` | 调优 | Integer | `3600` | 否 | 预检缓存时长（秒） |
| `freeway.http.cors.allow-credentials` | 调优 | Boolean | `false` | 否 | 允许携带凭证 |

#### 健康检查

| 键 | 档 | 类型 | 默认值 | 必填 | 说明 |
|----|------|------|--------|------|------|
| `freeway.http.health.enabled` | 姿态 | Boolean | `true` | 否 | 启用健康端点 |
| `freeway.http.health.path` | 调优 | String | `/healthz` | 否 | 健康检查路径 |

> cloud 探针不在上表：安装 `CloudHealthModule` 时以固定路径贡献
> `GET /health/live`（进程存活）与 `GET /health/ready`（依赖就绪聚合，
> 全健康 200、否则 503），路径**不可配**；与 `/healthz`
> （`freeway.http.health.path`）是两套端点，勿配成同一路径。详见
> `docs/freeway-cloud-design.md` §5.6。

#### 请求体

| 键 | 档 | 类型 | 默认值 | 必填 | 说明 |
|----|------|------|--------|------|------|
| `freeway.http.max-body-size` | 调优 | Long | `10485760` (10 MB) | 否 | 最大请求体大小（字节） |

#### SSL / HTTPS

| 键 | 档 | 类型 | 默认值 | 必填 | 说明 |
|----|------|------|--------|------|------|
| `freeway.http.ssl.key-store` | 姿态·presence | String | *(空)* | **生产是** | **presence 主键**——密钥库路径（PKCS12 或 JKS），非空即启用 HTTPS |
| `freeway.http.ssl.enabled` | 姿态 | Boolean | *(未设)* | 否 | 显式主开关，**设了就赢**：`true` 开（此时 key-store 必填）、`false` = 总闸（连已配置的 keystore 也压制，回明文）。未设时退到 presence 规则 |
| `freeway.http.ssl.key-store-password` | 必填·条件 | String | *(空)* | 启用 TLS 时 | 密钥库密码（代码不预检，缺失时在加载密钥库阶段报错）。键名含连字符，环境变量名也含连字符（`FREEWAY_HTTP_SSL_KEY-STORE-PASSWORD`）：用 `-D`、`env 'NAME=...'` 或容器 `-e` 注入；shell 的 `export` 不支持含 `-` 的名字 |
| `freeway.http.ssl.key-store-type` | 调优 | String | `PKCS12` | 否 | 密钥库类型 |
| `freeway.http.ssl.http2` | 调优 | Boolean | `true` | 否 | 通过 ALPN 启用 HTTP/2 over TLS |
| `freeway.http.ssl.trust-store` | 决策 | String | *(空)* | 否 | 可选信任库路径（mTLS 场景） |
| `freeway.http.ssl.trust-store-password` | 决策 | String | *(空)* | 否 | 信任库密码 |
| `freeway.http.ssl.trust-store-type` | 调优 | String | `PKCS12` | 否 | 信任库类型 |
| `freeway.http.ssl.client-auth` | 调优 | Boolean | `false` | 否 | 要求客户端证书（mTLS） |
| `freeway.http.ssl.protocols` | 调优 | String | *(空)* | 否 | 逗号分隔的 TLS 协议版本 |
| `freeway.http.ssl.ciphers` | 调优 | String | *(空)* | 否 | 逗号分隔的 TLS 密码套件 |
| `freeway.http.ssl.sni-directory` | 调优 | String | *(空)* | 否 | SNI 每主机名密钥库目录 |
| `freeway.http.ssl.reload-interval` | 调优 | Duration | `0` | 否 | 证书热重载间隔（文件监听即时触发、轮询兜底；`0` = 禁用热重载） |

#### HTTP/2

| 键 | 档 | 类型 | 默认值 | 必填 | 说明 |
|----|------|------|--------|------|------|
| `freeway.http.h2.reset-burst-limit` | 调优 | Integer | `200` | 否 | 入站 RST 突发熔断：窗口内未响应即取消超过此数即 GOAWAY(ENHANCE_YOUR_CALM) 并拆连接（`0` = 整个防护关闭） |
| `freeway.http.h2.reset-window` | 调优 | Duration | `10s` | 否 | RST 突发计数的滑动窗口。**与上一行是一对**：`reset-burst-limit=0` 时本键无意义（代码直接返回），所以二者不是两个独立决定 |

### 示例

```json
{
  "freeway": {
    "http": {
      "server": {
        "host": "0.0.0.0",
        "port": 8080,
        "backlog": 0,
        "shutdown-grace": "2s",
        "read-timeout": "30s",
        "write-timeout": "30s",
        "max-connections": 0,
        "receive-buffer-size": 0,
        "send-buffer-size": 0
      },
      "compression": {
        "enabled": true,
        "min-size": 256
      },
      "access-log": {
        "enabled": false
      },
      "cors": {
        "enabled": true,
        "allowed-origins": "*",
        "allowed-methods": "GET, POST, PUT, DELETE, PATCH, OPTIONS",
        "allowed-headers": "Content-Type, Authorization",
        "exposed-headers": "",
        "max-age": "3600",
        "allow-credentials": false
      },
      "health": {
        "enabled": true,
        "path": "/healthz"
      },
      "max-body-size": 10485760,
      "ssl": {
        "enabled": false,
        "key-store": "",
        "key-store-password": "",
        "key-store-type": "PKCS12",
        "http2": true,
        "trust-store": "",
        "trust-store-password": "",
        "trust-store-type": "PKCS12",
        "client-auth": false,
        "protocols": "",
        "ciphers": "",
        "sni-directory": "",
        "reload-interval": "0"
      }
    }
  }
}
```

---

## 四、DB — 数据库

### 配置项

#### 连接

| 键 | 档 | 类型 | 默认值 | 必填 | 说明 |
|----|------|------|--------|------|------|
| `freeway.db.url` | 必填 | String | *(无)* | **是** | JDBC 连接 URL，无默认值，启动时必检 |
| `freeway.db.username` | 必填 | String | *(无)* | **是** | 数据库用户名，无默认值，启动时必检 |
| `freeway.db.password` | 决策 | String | *(空)* | 否 | 数据库密码。生产环境通过环境变量 `FREEWAY_DB_PASSWORD` 注入 |
| `freeway.db.dialect` | 调优·哨兵 | String | *(空)* | 否 | SQL 方言（`postgresql`/`mysql`/`sqlite`/`h2`）。空则从 JDBC URL 自动检测；多数据源需显式指定 |
| `freeway.db.query-timeout` | 调优 | Duration | `15s` | 否 | 语句查询超时（`0` = 无超时）。报表类查询可调大 |

#### 连接池

| 键 | 档 | 类型 | 默认值 | 必填 | 说明 |
|----|------|------|--------|------|------|
| `freeway.db.pool.max-size` | 调优 | Integer | `10` | 否 | 连接池最大连接数。开发 `4`，生产按并发量调大 |
| `freeway.db.pool.min-idle` | 调优 | Integer | `2` | 否 | 最小空闲连接数 |
| `freeway.db.pool.connection-timeout` | 调优 | Duration | `10s` | 否 | 从池获取连接的最大等待时间 |
| `freeway.db.pool.max-lifetime` | 调优 | Duration | `30m` | 否 | 连接最大存活时间 |
| `freeway.db.pool.max-idle-time` | 调优 | Duration | `10m` | 否 | 连接最大空闲时间 |
| `freeway.db.pool.clean-interval` | 调优 | Duration | `2m` | 否 | 空闲驱逐清理周期 |
| `freeway.db.pool.health-check-query` | 调优 | String | *(空)* | 否 | 健康检查 SQL（空则使用 JDBC `isValid()`）。`SELECT 1` 显式验证 |
| `freeway.db.pool.health-check-timeout` | 调优 | Duration | `5s` | 否 | 健康检查查询超时 |

#### Schema

| 键 | 档 | 类型 | 默认值 | 必填 | 说明 |
|----|------|------|--------|------|------|
| `freeway.db.schema.auto` | 姿态 | Boolean | `true` | 否 | 启动时自动生成 Schema DDL。**开发 `true`（零摩擦迭代）；生产 `false`（必须用迁移文件）** |
| `freeway.db.schema.groups` | 调优 | String | *(空)* | 否 | 逗号分隔的 Schema 组过滤。空 = 所有组 |

#### 迁移

| 键 | 档 | 类型 | 默认值 | 必填 | 说明 |
|----|------|------|--------|------|------|
| `freeway.db.migration.enabled` | 姿态 | Boolean | `true` | 否 | 启用 SQL 迁移执行。**生产必须为 `true`** |
| `freeway.db.migration.path` | 调优 | String | `db/migration/` | 否 | 迁移 SQL 文件所在类路径目录 |
| `freeway.db.migration.table` | 调优 | String | `_migrations` | 否 | 迁移跟踪表名 |
| `freeway.db.migration.lock-ttl` | 调优 | Duration | *(运行器默认 1h)* | 否 | 锁行存活时长（ISO-8601，如 `PT1H`）。空则使用运行器默认。`0` 或负值禁用过期锁抢占。多实例部署建议设置 |

### 示例

```json
{
  "freeway": {
    "db": {
      "url": "jdbc:postgresql://localhost:5432/mydb",
      "username": "postgres",
      "password": "",
      "dialect": "",
      "query-timeout": "15s",
      "pool": {
        "max-size": 10,
        "min-idle": 2,
        "connection-timeout": "10s",
        "max-lifetime": "30m",
        "max-idle-time": "10m",
        "clean-interval": "2m",
        "health-check-query": "",
        "health-check-timeout": "5s"
      },
      "schema": {
        "auto": true,
        "groups": ""
      },
      "migration": {
        "enabled": true,
        "path": "db/migration/",
        "table": "_migrations",
        "lock-ttl": ""
      }
    }
  }
}
```

---

## 五、Cloud — 云原生

### 配置项

**分档**（规则见开头"配置分类规则"）：决策 = `event.peers` + `event.token`、
`event.subscriptions` + 两个白名单、`rpc.tls.key-store` + `-password`（走 mTLS 时）、
`registry.service-host`（`auto` 在容器里通常够用，多网卡主机需点名）；姿态 = 各
`.enabled`、presence 主键，以及三个 `auto` 键（`registry.service-scheme` /
`.service-host` / `registry.shutdown-drain`）；声明 = 四个 `*.type`；机制 =
`secret.file`/`secret.keys`（仅 `-D`）；其余为**调优**，且每簇已有一个键在管
（`rpc.resilience=auto|off`、`event.enabled` presence、`event.dedup.enabled`）。

**两类键，别混淆**（云后端相关键尤其容易）：

| 类别 | 例子 | 作用 |
|---|---|---|
| **选择键** | `.primary()` 绑定、`@Local` 标记、适配器模块本身 | **真正决定用哪个实现**：容器解析时主绑定胜出，本地默认让位 |
| **声明键** | `freeway.cloud.discovery.type` / `registry.type` / `secret.type` / `storage.type` | **只表达意图**："我期望用 Nacos / Consul / S3 / Vault"。框架**不用它选择实现**（没有类名反射加载，也没有 classpath 扫描），它由适配器自己消费，并在"声明了外部后端、本地实现却仍是活跃绑定"时触发一次启动告警（`BackendTypeGuard`），避免把声明当成生效 |

所以：想换后端，写适配器模块并 `.primary()`；`*.type` 是给人和适配器看的
声明与自检，写错（或没装适配器）不会静默生效，而是启动告警一次。

#### 密钥

| 键 | 档 | 类型 | 默认值 | 必填 | 说明 |
|----|------|------|--------|------|------|
| `freeway.cloud.secret.type` | 声明 | String | *(空)* | 否 | 密钥后端类型。空（或 `local`）= 内置 env/文件实现；外部后端（Vault 等）由适配器 `.primary()` 接入（freeway-ext 未交付）——配了外部值而本地实现仍生效时启动告警一次 |
| `freeway.cloud.secret.file` | 机制·-D | String | `application-secrets.properties` | 否 | 密钥文件路径（key=value 格式）。**仅 `-D` 系统属性生效**：密钥提供方参与符号解析，其自身配置不能经该链读取，写进配置文件/环境变量无效。文件被替换（size/mtime 变化）时**无需重启**即生效（最多 1s 节流）；读失败或文件瞬时消失时保留已加载的值并告警 |
| `freeway.cloud.secret.keys` | 机制·-D | String | *(空)* | 否 | 允许从密钥存储解析的符号名白名单（逗号分隔）。**仅 `-D` 系统属性生效**（同上）。留空即"对任意符号名查环境变量"的锋利默认，启动时打 WARN |

#### 对象存储

| 键 | 档 | 类型 | 默认值 | 必填 | 说明 |
|----|------|------|--------|------|------|
| `freeway.cloud.storage.type` | 声明 | String | *(空)* | 否 | 存储后端类型。空（或 `local`）= 内置本地文件系统实现；外部后端（S3 等）由适配器 `.primary()` 接入（freeway-ext 未交付）——配了外部值而本地实现仍生效时启动告警一次 |
| `freeway.cloud.storage.base-path` | 调优 | String | `cloud-storage` | 否 | 本地后端根路径（相对工作目录，如 `cloud-storage`；绝对路径亦可） |

#### 服务发现 / 注册

| 键 | 档 | 类型 | 默认值 | 必填 | 说明 |
|----|------|------|--------|------|------|
| `freeway.cloud.discovery.type` | 声明 | String | *(空)* | 否 | 发现后端类型。空（或 `local`）= 内置进程内注册表；外部后端（Nacos 等）由适配器 `.primary()` 接入（freeway-ext 未交付）——配了外部值而本地实现仍生效时启动告警一次 |
| `freeway.cloud.registry.type` | 声明 | String | *(空)* | 否 | 注册后端类型。同上（空/`local` = 进程内注册表） |
| `freeway.cloud.registry.service-id` | 调优·哨兵 | String | *(空)* | 否 | 注册的逻辑服务名。空 = 回退 `freeway.app.name`（再回退 `freeway-app`） |
| `freeway.app.name` | 调优·哨兵 | String | `freeway-app` | 否 | 服务名的通用回退键，cloud 注册时读取；注意 JVM `-D app.name`（无 `freeway.` 前缀）是另一回事——它只决定默认日志文件名 |
| `freeway.cloud.registry.service-host` | 姿态·auto | String | `auto` | 否 | 注册地址主机名。`auto` = 绑定地址若是具体地址就用它（服务器只在那里监听）；绑定 `0.0.0.0`/`::` 时优先 `POD_IP`、否则首个可路由本地地址，都没有才回落绑定地址并告警。多网卡主机请点名 |
| `freeway.cloud.registry.service-scheme` | 姿态·auto | String | `auto` | 否 | 注册协议，同时决定事件网格拨号用 `ws` 还是 `wss`。`auto` = 跟随 HTTP 服务器是否启用 TLS；显式值只接受 `http`/`https`，其它值启动失败 |
| `freeway.cloud.registry.shutdown-drain` | 姿态·auto | Duration | `auto` | 否 | **停机 drain 窗口**：摘除注册并置 readiness 为 draining 之后，继续服务这段时间再停。`auto` = 由注册表后端回答（`ServiceRegistry.drainWindow()`）：内置进程内注册表答 `0s`（同 JVM 无传播延迟），注册中心适配器自带传播窗口（如 `5s`），因此不必每个部署各写一遍；显式时长优先，负值启动失败 |
| `freeway.cloud.registry.service-port` | 调优·哨兵 | Integer | *(空)* | 否 | 注册端口。空 = HTTP server 实际监听端口 |
| `freeway.cloud.registry.service-instance-id` | 调优·哨兵 | String | *(空)* | 否 | 实例级稳定标识。空 = 派生键 `service-id@host:port` |

#### RPC / 远程调用

| 键 | 档 | 类型 | 默认值 | 必填 | 说明 |
|----|------|------|--------|------|------|
| `freeway.cloud.rpc.connect-timeout` | 调优 | Long | `3000` | 否 | 连接超时（毫秒） |
| `freeway.cloud.rpc.request-timeout` | 调优 | Long | `10000` | 否 | 请求超时（毫秒） |
| `freeway.cloud.rpc.shutdown-grace` | 调优 | Duration | `5s` | 否 | **出站调用收尾窗口**：停机时先拒绝新调用，再等已在飞的调用完成，超时仍未有结果的才被以 `CloudHttpClient is closed` 失败。空转进程立即关闭（没有在飞调用就没有等待），所以默认值只在真有调用时付出时间；请压到部署的 terminationGracePeriod 以内 |
| `freeway.cloud.rpc.trace.enabled` | 姿态 | Boolean | `true` | 否 | 启用 RPC 调用链路追踪 |
| `freeway.cloud.rpc.resilience` | 调优·聚合闸 | String | `auto` | 否 | **聚合开关**：`auto` = 下方细项键各自生效；`off` = 总闸（不重试、熔断 NOOP、限流无限），忽略全部细项键。用于 mesh 接管（平台已做重试/熔断，应用层需退位）与故障诊断隔离变量。非法值启动即失败 |

#### 弹性 — 重试

| 键 | 档 | 类型 | 默认值 | 必填 | 说明 |
|----|------|------|--------|------|------|
| `freeway.cloud.rpc.retry.max-attempts` | 调优 | Integer | `3` | 否 | 最大重试次数（`resilience=off` 时忽略） |
| `freeway.cloud.rpc.retry.backoff-base` | 调优 | Long | `100` | 否 | 重试退避基数（毫秒） |
| `freeway.cloud.rpc.retry.backoff-max` | 调优 | Long | `5000` | 否 | 最大退避时间（毫秒） |

重试经过两道门：失败类别（连接失败/超时/中途 I/O/5xx 可重试，4xx 与本地拒绝不重试）与
**幂等门**——timeout/中途 I/O/5xx 属"结果未知"（对端可能已执行请求），仅对幂等操作重放。
幂等性由请求携带：按 HTTP 动词派生（`GET/HEAD/PUT/DELETE/OPTIONS/TRACE` 幂等，`POST/PATCH` 否），
`CloudRequest.idempotentWith(true)` 显式覆盖；远程调用经 consumer 接口的 `@Idempotent`
注解（方法级/接口级）声明。连接类失败请求未送达，任何操作都可重试。

#### 弹性 — 熔断器

| 键 | 档 | 类型 | 默认值 | 必填 | 说明 |
|----|------|------|--------|------|------|
| `freeway.cloud.rpc.circuit-breaker.enabled` | 姿态 | Boolean | `true` | 否 | 启用熔断器——默认就是生产姿态，只在明确要关时写 `false`（`resilience=off` 时忽略） |
| `freeway.cloud.rpc.circuit-breaker.failure-threshold` | 调优 | Integer | `5` | 否 | 熔断触发阈值（滑动窗口内失败数） |
| `freeway.cloud.rpc.circuit-breaker.failure-window` | 调优 | Long | `60` | 否 | 滑动窗口时长（秒） |
| `freeway.cloud.rpc.circuit-breaker.open-window` | 调优 | Long | `30` | 否 | 熔断打开状态持续时长（秒） |

#### 弹性 — 限流

| 键 | 档 | 类型 | 默认值 | 必填 | 说明 |
|----|------|------|--------|------|------|
| `freeway.cloud.rpc.rate-limit.enabled` | 姿态 | Boolean | `false` | 否 | 启用限流。按需开启（`resilience=off` 时忽略） |
| `freeway.cloud.rpc.rate-limit.per-second` | 调优 | Double | `100` | 否 | 每秒最大请求数 |

#### CloudEventBus — 跨节点事件网格

| 键 | 档 | 类型 | 默认值 | 必填 | 说明 |
|----|------|------|--------|------|------|
| `freeway.cloud.event.peers` | 姿态·presence | String | *(空)* | 否 | **对等节点列表（presence 主键）**——非空即启用 mesh；空 = 纯监听方需显式 `enabled=true`。启用前确认网络可达 |
| `freeway.cloud.event.enabled` | 姿态 | Boolean | *(未设)* | 否 | 显式主开关，**设了就赢**：`true` 开（含无 peers 的 discovery-fed mesh）、`false` = 总闸（连已配置的 peers 也压制）。未设时退到 presence 规则（peers 非空即开）。什么都不设 = 模块装了也不动 |
| `freeway.cloud.event.subscriptions` | 决策 | String | *(空)* | 否 | 订阅列表 |
| `freeway.cloud.event.allowed-types` | 决策 | String | *(空)* | 否 | CLASS 通道反序列化白名单，**空 = 拒绝全部**（deny-by-default，不回退到"放行任意类"） |
| `freeway.cloud.event.allowed-topics` | 决策 | String | *(空)* | 否 | TOPIC 通道白名单，空 = 放行全部 |
| `freeway.cloud.event.token` | 决策 | String | *(空)* | 否 | Mesh 握手共享密钥（空 = 无对等认证）。**多节点生产必配**：全节点值一致、经 `FREEWAY_CLOUD_EVENT_TOKEN` 注入；不一致以 WS `1008` 断开，轮换需滚动重启 |
| `freeway.cloud.event.dedup.enabled` | 姿态 | Boolean | `false` | 否 | 启用事件去重（消耗内存，按需开启） |
| `freeway.cloud.event.dedup.capacity` | 调优 | Integer | `4096` | 否 | 去重 ID 缓存容量 |
| `freeway.cloud.event.connect-timeout-ms` | 调优 | Long | `3000` | 否 | 出站拨号 socket 连接超时（毫秒） |
| `freeway.cloud.event.handshake-timeout-ms` | 调优 | Long | `10000` | 否 | 握手看门狗：连接建立后等待 hello/ack 的超时（毫秒） |
| `freeway.cloud.event.backoff-base-ms` | 调优 | Long | `1000` | 否 | 断线重连退避基数（毫秒，指数退避） |
| `freeway.cloud.event.backoff-max-ms` | 调优 | Long | `30000` | 否 | 断线重连退避上限（毫秒） |

#### RPC / TLS

| 键 | 档 | 类型 | 默认值 | 必填 | 说明 |
|----|------|------|--------|------|------|
| `freeway.cloud.rpc.tls.key-store` | 姿态·presence | String | *(空)* | 否 | **presence 主键**——RPC 客户端密钥库路径，非空即启用 mTLS；空 = 明文开发默认 |
| `freeway.cloud.rpc.tls.key-store-password` | 必填·条件 | String | *(空)* | 否 | RPC 客户端密钥库密码 |
| `freeway.cloud.rpc.tls.trust-store` | 决策 | String | *(空)* | 否 | RPC 客户端信任库路径 |
| `freeway.cloud.rpc.tls.trust-store-password` | 决策 | String | *(空)* | 否 | RPC 客户端信任库密码 |

#### Auth 传播

| 键 | 档 | 类型 | 默认值 | 必填 | 说明 |
|----|------|------|--------|------|------|
| `freeway.cloud.auth.extract.enabled` | 姿态 | Boolean | `false` | 否 | 启用 inbound `x-principal` 提取（仅信任服务网内部） |

### 示例

```json
{
  "freeway": {
    "cloud": {
      "secret": {
        "type": ""
      },
      "storage": {
        "type": "",
        "base-path": "cloud-storage"
      },
      "discovery": {
        "type": ""
      },
      "registry": {
        "type": "",
        "service-id": "",
        "service-host": "",
        "service-scheme": "http",
        "service-port": 0,
        "service-instance-id": ""
      },
      "rpc": {
        "connect-timeout": 3000,
        "request-timeout": 10000,
        "trace": {
          "enabled": true
        },
        "retry": {
          "max-attempts": 3,
          "backoff-base": 100,
          "backoff-max": 5000
        },
        "circuit-breaker": {
          "enabled": true,
          "failure-threshold": 5,
          "failure-window": 60,
          "open-window": 30
        },
        "rate-limit": {
          "enabled": false,
          "per-second": 100
        },
        "tls": {
          "key-store": "",
          "key-store-password": "",
          "trust-store": "",
          "trust-store-password": ""
        }
      },
      "event": {
        "enabled": false,
        "peers": "",
        "subscriptions": "",
        "allowed-types": "",
        "allowed-topics": "",
        "token": "",
        "dedup": {
          "enabled": false,
          "capacity": 4096
        },
        "connect-timeout-ms": 3000,
        "handshake-timeout-ms": 10000,
        "backoff-base-ms": 1000,
        "backoff-max-ms": 30000
      },
      "auth": {
        "extract": {
          "enabled": false
        }
      }
    }
  }
}
```

> 注：`secret.file` / `secret.keys` 仅 `-D` 系统属性生效，不参与文件
> 级联，故未列入上述 JSON 示例（见密钥表）。

---

## 六、Flow — 工作流引擎

Flow 模块不提供外部化配置键。所有配置通过编程式 API 完成：

- **GraphSpec** 构建器：`GraphSpec.create("id", spec -> { ... })`
- **NodeSpec** 构建器：`.metaPut(key, value)`, `.when(...)`, `.task(...)`, `.linkAdd(to, config)`
- **节点类型**：START / END / ACTIVITY / EXCLUSIVE / INCLUSIVE / PARALLEL / LOOP
- **图版本**：v2 DAG 格式（`GraphSpec.VERSION = 2`）
- **执行约束**：最大递归深度 `1000`，最大 LOOP 迭代 `100_000`
- **PlantUML**：`PlantUmlOptions` 控制输出格式

---

## 七、IoC — 容器

IoC 容器不提供外部化配置键。所有配置通过编程式 API 完成：

- **绑定**：`binder.bind(X.class).to(Y.class)` / `.to(c -> ...)`
- **作用域**：`bind().scope(SINGLETON | PROTOTYPE | THREAD)`
- **`.primary()`**：引擎/池/方言选择 — 默认实现绑定无 `.primary()`，扩展模块绑定替代实现时带 `.primary()`，容器自动解析
- **注入**：`@Inject`、`@Symbol`（严格查找或表达式展开）
- **扩展**：`Contribution<RuntimeHook>`、`Extension` 机制
- **Bean 选择**：通过 `binding.primary()` 而非注解

---

## 配置类型参考

| 类型 | 格式 | 示例 |
|------|------|------|
| String | 文本 | `"127.0.0.1"` |
| Integer | 数字 | `8080` |
| Long | 数字 | `10485760` |
| Double | 数字 | `100.0` |
| Boolean | `true` / `false` | `true` |
| Duration | ISO-8601 或后缀 | `2s`, `30s`, `10m`, `30m`, `PT1H` |
| List(String) | 逗号分隔（与 HTTP 头多值约定同形）| `a,b,c`；条目两端空白自动去除、空条目丢弃；未设 / `""` / `" , ,"` 一律空列表 |

支持的后缀：`ms`（毫秒）、`s`（秒）、`m`（分钟）、`h`（小时）

**列表键的两种写法**：properties/env/CLI 用逗号字符串；application.json 可写原生数组（展平时自动连接为同一逗号编码），两者等价——

```json
{ "freeway": { "cloud": { "event": { "peers": ["10.0.0.11:8080", "10.0.0.12:8080"] } } } }
```

等价于 `freeway.cloud.event.peers=10.0.0.11:8080,10.0.0.12:8080`。列表条目不能包含逗号（与 HTTP 头列表同样的限制）。

---

## 环境变量映射

默认前缀 `FREEWAY_`，下划线转点号：
- `freeway.http.server.port` → `FREEWAY_HTTP_SERVER_PORT`
- `freeway.db.url` → `FREEWAY_DB_URL`
- `freeway.log.level` → `FREEWAY_LOG_LEVEL`

映射规则只有一条，没有例外：**键名的 `.` 换成 `_`，其余字符原样保留**。`-` 是普通字符，不参与转换——`key-store` 与 `key.store` 是两个不同的键，绝不能让同一个变量同时喂给它们（那正是"折叠"式映射的歧义来源，本框架不采用）。

因此含连字符的键，其环境变量名里就是**字面连字符**：

```
freeway.http.ssl.key-store-password  →  FREEWAY_HTTP_SSL_KEY-STORE-PASSWORD
freeway.db.pool.max-size             →  FREEWAY_DB_POOL_MAX-SIZE
```

shell 的 `export` 不接受含 `-` 的名字，这是 shell 的限制、不是映射的例外：用 `-D`、`env 'NAME=value'`、systemd `Environment=` 或容器 `-e` 传入。变量名写错（例如用 `_` 代替 `-`）不会被特殊处理——它会映射成另一个键名；若没人声明那个键，它就像任何拼错的键一样静默无效（与 CLI 拼错参数的行为一致）。

例外（读自 JVM 系统属性，不参与上述级联）：`freeway.cloud.secret.file`、
`freeway.cloud.secret.keys` —— 两者只能 `-D` 设置，否则静默无效。

自定义前缀 `freeway.env.prefix=APP_`：`APP_SERVER_PORT` → `server.port`（透传）
