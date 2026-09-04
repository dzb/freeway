# Freeway 配置参考

> 所有配置项采用点号分隔的层级键，统一在 `freeway.*` 命名空间下。
> 配置来源优先级（低 → 高）：`application.properties` → `application.json` → `application-{profile}.properties` → `application-{profile}.json` → 环境变量（`FREEWAY_*`） → CLI 参数（`--key=value`）。
> 详见 [CLAUDE.md](CLAUDE.md) 配置级联章节。

---

## 必填配置速查

以下配置项**必须设置**，其余均有合理默认值，非必要无需改动：

| 模块 | 必填键 | 说明 |
|------|--------|------|
| **DB** | `freeway.db.url` | JDBC 连接 URL，无默认值，启动时必检 |
| **DB** | `freeway.db.username` | 数据库用户名，无默认值，启动时必检 |
| **DB** | `freeway.db.password` | 数据库密码（可为空，生产环境通过环境变量 `FREEWAY_DB_PASSWORD` 注入） |
| **HTTP** | `freeway.http.ssl.enabled=true` + 证书 | 生产环境启用 HTTPS 时必填；开发环境可保持 `false` |
| **Cloud** | 全部可选 | 仅在使用 `freeway-cloud` 功能时需要 |

> 其余所有配置项均有默认值，使用默认值即可正常运行，无需改动。

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

| 键 | 类型 | 默认值 | 必填 | 说明 |
|----|------|--------|------|------|
| `freeway.profile` | String | *(无)* | **是** | 激活的 Profile，支持逗号分隔多个。激活后加载 `application-{profile}.*`。开发用 `dev`，生产用 `prod` |
| `freeway.config.file` | String | *(空)* | 否 | 额外配置文件路径（JVM 系统属性 `-D`），多个逗号分隔。参与文件级热重载 |
| `freeway.env.prefix` | String | `FREEWAY_` | 否 | 环境变量前缀。仅 JVM 系统属性生效；自定义前缀时 `APP_SERVER_PORT` → `server.port`（透传） |

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

### 配置项

#### 全局日志

| 键 | 类型 | 默认值 | 必填 | 说明 |
|----|------|--------|------|------|
| `freeway.log.level` | String | `INFO` | 否 | 全局日志级别。SLF4J 名（TRACE/DEBUG/INFO/WARN/OFF）或 JUL 名（FINEST/FINE/INFO/WARNING/SEVERE） |
| `freeway.log.format` | String | `auto` | 否 | 格式化模式：`auto`（Freeway formatter + ANSI）或 `simple`（JUL SimpleFormatter） |
| `freeway.log.color` | String | `auto` | 否 | 颜色模式：`auto`（跟随 format）、`always`（强制 ANSI）、`never`（强制无色）。遵循 `NO_COLOR` 规范 |
| `freeway.log.caller-info` | Boolean | `true` | 否 | 每条日志解析 source class/method。日志量大时可设为 `false` 提升吞吐 |
| `freeway.log.mdc` | Boolean | `true` | 否 | 日志输出是否包含 MDC 上下文。MDC（Mapped Diagnostic Context）是每线程级的上下文键值对，用于分布式链路追踪、请求关联等场景 |
| `freeway.log.mdc.priority` | String | `code,market,diagId` | 否 | MDC 键的显示优先级顺序（逗号分隔），其余键按字母序排列。`code`=业务码、`market`=市场、`diagId`=诊断ID，用于跨服务请求关联 |

#### 控制台输出

| 键 | 类型 | 默认值 | 必填 | 说明 |
|----|------|--------|------|------|
| `freeway.log.console.enabled` | Boolean | `true` | 否 | 是否启用控制台处理器 |
| `freeway.log.console.level` | String | *(继承 root)* | 否 | 控制台处理器级别。空则继承全局级别 |

#### 默认文件日志

| 键 | 类型 | 默认值 | 必填 | 说明 |
|----|------|--------|------|------|
| `freeway.log.file` | String | `auto` | 否 | 文件日志模式：`auto`（全托管 `logs/{app.name}.log`）或 `off`（禁用） |
| `freeway.log.file.max-size` | Long | `104857600` (100 MB) | 否 | 大小滚动阈值（字节） |
| `freeway.log.file.max-history` | Integer | `30` | 否 | 归档保留天数 |
| `freeway.log.file.compress` | Boolean | `true` | 否 | 归档后是否 GZIP 压缩 |
| `freeway.log.file.flush-interval` | Integer | `250` | 否 | 后台刷盘间隔（毫秒）。`0` = 每条立即刷盘（最保险，吞吐低） |

#### 多文件日志

| 键 | 类型 | 默认值 | 必填 | 说明 |
|----|------|--------|------|------|
| `freeway.log.files` | String | *(空)* | 否 | 逗号分隔的命名文件列表（如 `biz,audit`） |
| `freeway.log.file.<name>.path` | String | *(必填)* | 是 | 命名文件的路径（命名文件场景下必填） |
| `freeway.log.file.<name>.logger` | String | *(root)* | 否 | 绑定到命名文件的 Logger 名 |
| `freeway.log.file.<name>.level` | String | *(继承父)* | 否 | 命名文件的日志级别 |
| `freeway.log.file.<name>.max-size` | Long | `104857600` | 否 | 命名文件大小阈值 |
| `freeway.log.file.<name>.max-history` | Integer | `30` | 否 | 命名文件归档保留天数 |
| `freeway.log.file.<name>.compress` | Boolean | `true` | 否 | 命名文件是否 GZIP 压缩 |
| `freeway.log.file.<name>.flush-interval` | Integer | `250` | 否 | 命名文件刷盘间隔 |

#### 按包/类粒度级别

| 键 | 类型 | 默认值 | 必填 | 说明 |
|----|------|--------|------|------|
| `<logger-name>.level` | String | *(继承父)* | 否 | 按包/类设置日志级别，如 `com.zaxxer.hikari.level=WARNING` |

### 示例

```json
{
  "freeway": {
    "log": {
      "level": "INFO",
      "format": "auto",
      "color": "auto",
      "caller-info": true,
      "mdc": true,
      "mdc.priority": "code,market,diagId",
      "console": {
        "enabled": true,
        "level": "INFO"
      },
      "file": "auto",
      "max-size": 104857600,
      "max-history": 30,
      "compress": true,
      "flush-interval": 250,
      "files": "biz,audit"
    }
  }
}
```

---

## 三、HTTP — Web 服务器

### 配置项

#### 服务器

| 键 | 类型 | 默认值 | 必填 | 说明 |
|----|------|--------|------|------|
| `freeway.http.server.host` | String | `127.0.0.1` | 否 | 绑定地址。开发 `127.0.0.1`，生产 `0.0.0.0`（由 Nginx 等反向代理转发） |
| `freeway.http.server.port` | Integer | `8080` | 否 | 监听端口（`0` = 系统分配） |
| `freeway.http.server.backlog` | Integer | `0` | 否 | Accept 队列大小（`0` = 系统默认） |
| `freeway.http.server.shutdown-grace` | Duration | `2s` | 否 | 关闭时等待 in-flight 请求的时间。开发 `2s`，生产 `30s`（滚动部署） |
| `freeway.http.server.read-timeout` | Duration | `30s` | 否 | Socket 读空闲超时（`0` = 禁用） |
| `freeway.http.server.write-timeout` | Duration | `30s` | 否 | 单次写操作超时（`0` = 禁用） |
| `freeway.http.server.max-connections` | Integer | `0` | 否 | 最大并发连接数（`0` = 不限） |
| `freeway.http.server.receive-buffer-size` | Integer | `0` | 否 | SO_RCVBUF（`0` = OS 默认） |
| `freeway.http.server.send-buffer-size` | Integer | `0` | 否 | SO_SNDBUF（`0` = OS 默认） |

#### 压缩

| 键 | 类型 | 默认值 | 必填 | 说明 |
|----|------|--------|------|------|
| `freeway.http.compression.enabled` | Boolean | `true` | 否 | 启用 gzip 响应压缩 |
| `freeway.http.compression.min-size` | Integer | `256` | 否 | 压缩最小响应体（字节） |

#### 访问日志

| 键 | 类型 | 默认值 | 必填 | 说明 |
|----|------|--------|------|------|
| `freeway.http.access-log.enabled` | Boolean | `false` | 否 | 启用文本访问日志（stdout 输出 method path status elapsed-ms） |

#### CORS

| 键 | 类型 | 默认值 | 必填 | 说明 |
|----|------|--------|------|------|
| `freeway.http.cors.enabled` | Boolean | `true` | 否 | 启用 CORS 过滤 |
| `freeway.http.cors.allowed-origins` | String | `*` | 否 | 允许的源。生产环境必须指定具体域名，禁 `*` |
| `freeway.http.cors.allowed-methods` | String | `GET, POST, PUT, DELETE, PATCH, OPTIONS` | 否 | 允许的方法 |
| `freeway.http.cors.allowed-headers` | String | `Content-Type, Authorization` | 否 | 允许的请求头 |
| `freeway.http.cors.exposed-headers` | String | *(空)* | 否 | 暴露的响应头 |
| `freeway.http.cors.max-age` | String | `3600` | 否 | 预检缓存时长（秒） |
| `freeway.http.cors.allow-credentials` | Boolean | `false` | 否 | 允许携带凭证 |

#### 健康检查

| 键 | 类型 | 默认值 | 必填 | 说明 |
|----|------|--------|------|------|
| `freeway.http.health.enabled` | Boolean | `true` | 否 | 启用健康端点 |
| `freeway.http.health.path` | String | `/healthz` | 否 | 健康检查路径 |

> cloud 探针不在上表：安装 `CloudHealthModule` 时以固定路径贡献
> `GET /health/live`（进程存活）与 `GET /health/ready`（依赖就绪聚合，
> 全健康 200、否则 503），路径**不可配**；与 `/healthz`
> （`freeway.http.health.path`）是两套端点，勿配成同一路径。详见
> `docs/freeway-cloud-unified-design.md` §5.7。

#### 请求体

| 键 | 类型 | 默认值 | 必填 | 说明 |
|----|------|--------|------|------|
| `freeway.http.max-body-size` | Long | `10485760` (10 MB) | 否 | 最大请求体大小（字节） |

#### SSL / HTTPS

| 键 | 类型 | 默认值 | 必填 | 说明 |
|----|------|--------|------|------|
| `freeway.http.ssl.enabled` | Boolean | `false` | **生产是** | 启用 HTTPS。生产环境必须为 `true` 并配置证书；开发可保持 `false` |
| `freeway.http.ssl.key-store` | String | *(空)* | **生产是** | 密钥库路径（PKCS12 或 JKS）。`ssl.enabled=true` 时必填 |
| `freeway.http.ssl.key-store-password` | String | *(空)* | **生产是** | 密钥库密码。建议通过环境变量 `FREEWAY_HTTP_SSL_KEY_STORE_PASSWORD` 注入 |
| `freeway.http.ssl.key-store-type` | String | `PKCS12` | 否 | 密钥库类型 |
| `freeway.http.ssl.http2` | Boolean | `true` | 否 | 通过 ALPN 启用 HTTP/2 over TLS |
| `freeway.http.ssl.trust-store` | String | *(空)* | 否 | 可选信任库路径（mTLS 场景） |
| `freeway.http.ssl.trust-store-password` | String | *(空)* | 否 | 信任库密码 |
| `freeway.http.ssl.trust-store-type` | String | `PKCS12` | 否 | 信任库类型 |
| `freeway.http.ssl.client-auth` | Boolean | `false` | 否 | 要求客户端证书（mTLS） |
| `freeway.http.ssl.protocols` | String | *(空)* | 否 | 逗号分隔的 TLS 协议版本 |
| `freeway.http.ssl.ciphers` | String | *(空)* | 否 | 逗号分隔的 TLS 密码套件 |
| `freeway.http.ssl.sni-directory` | String | *(空)* | 否 | SNI 每主机名密钥库目录 |
| `freeway.http.ssl.reload-interval` | Duration | `0` | 否 | 证书自动重载轮询间隔（`0` = 禁用） |

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

| 键 | 类型 | 默认值 | 必填 | 说明 |
|----|------|--------|------|------|
| `freeway.db.url` | String | *(无)* | **是** | JDBC 连接 URL，无默认值，启动时必检 |
| `freeway.db.username` | String | *(无)* | **是** | 数据库用户名，无默认值，启动时必检 |
| `freeway.db.password` | String | *(空)* | 否 | 数据库密码。生产环境通过环境变量 `FREEWAY_DB_PASSWORD` 注入 |
| `freeway.db.dialect` | String | *(空)* | 否 | SQL 方言（`postgresql`/`mysql`/`sqlite`/`h2`）。空则从 JDBC URL 自动检测；多数据源需显式指定 |
| `freeway.db.query-timeout` | Duration | `15s` | 否 | 语句查询超时（`0` = 无超时）。报表类查询可调大 |

#### 连接池

| 键 | 类型 | 默认值 | 必填 | 说明 |
|----|------|--------|------|------|
| `freeway.db.pool.max-size` | Integer | `10` | 否 | 连接池最大连接数。开发 `4`，生产按并发量调大 |
| `freeway.db.pool.min-idle` | Integer | `2` | 否 | 最小空闲连接数 |
| `freeway.db.pool.connection-timeout` | Duration | `10s` | 否 | 从池获取连接的最大等待时间 |
| `freeway.db.pool.max-lifetime` | Duration | `30m` | 否 | 连接最大存活时间 |
| `freeway.db.pool.max-idle-time` | Duration | `10m` | 否 | 连接最大空闲时间 |
| `freeway.db.pool.clean-interval` | Duration | `2m` | 否 | 空闲驱逐清理周期 |
| `freeway.db.pool.health-check-query` | String | *(空)* | 否 | 健康检查 SQL（空则使用 JDBC `isValid()`）。`SELECT 1` 显式验证 |
| `freeway.db.pool.health-check-timeout` | Duration | `5s` | 否 | 健康检查查询超时 |
| `freeway.db.query-timeout` | Duration | `15s` | 否 | 语句查询超时（`0` = 无超时） |

#### Schema

| 键 | 类型 | 默认值 | 必填 | 说明 |
|----|------|--------|------|------|
| `freeway.db.schema.auto` | Boolean | `true` | 否 | 启动时自动生成 Schema DDL。**开发 `true`（零摩擦迭代）；生产 `false`（必须用迁移文件）** |
| `freeway.db.schema.groups` | String | *(空)* | 否 | 逗号分隔的 Schema 组过滤。空 = 所有组 |

#### 迁移

| 键 | 类型 | 默认值 | 必填 | 说明 |
|----|------|--------|------|------|
| `freeway.db.migration.enabled` | Boolean | `true` | 否 | 启用 SQL 迁移执行。**生产必须为 `true`** |
| `freeway.db.migration.path` | String | `db/migration/` | 否 | 迁移 SQL 文件所在类路径目录 |
| `freeway.db.migration.table` | String | `_migrations` | 否 | 迁移跟踪表名 |
| `freeway.db.migration.lock-ttl` | Duration | *(运行器默认 1h)* | 否 | 锁行存活时长（ISO-8601，如 `PT1H`）。空则使用运行器默认。`0` 或负值禁用过期锁抢占。多实例部署建议设置 |

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

#### 密钥

| 键 | 类型 | 默认值 | 必填 | 说明 |
|----|------|--------|------|------|
| `freeway.cloud.secret.type` | String | *(空)* | 否 | 密钥后端类型。空（或 `local`）= 内置 env/文件实现；外部后端（Vault 等）由适配器 `.primary()` 接入（freeway-ext 未交付）——配了外部值而本地实现仍生效时启动告警一次 |
| `freeway.cloud.secret.file` | String | `application-secrets.properties` | 否 | 密钥文件路径（key=value 格式）。**仅 `-D` 系统属性生效**：密钥提供方参与符号解析，其自身配置不能经该链读取，写进配置文件/环境变量无效 |
| `freeway.cloud.secret.keys` | String | *(空)* | 否 | 允许从密钥存储解析的符号名白名单（逗号分隔）。**仅 `-D` 系统属性生效**（同上）。留空即"对任意符号名查环境变量"的锋利默认，启动时打 WARN |

#### 对象存储

| 键 | 类型 | 默认值 | 必填 | 说明 |
|----|------|--------|------|------|
| `freeway.cloud.storage.type` | String | *(空)* | 否 | 存储后端类型。空（或 `local`）= 内置本地文件系统实现；外部后端（S3 等）由适配器 `.primary()` 接入（freeway-ext 未交付）——配了外部值而本地实现仍生效时启动告警一次 |
| `freeway.cloud.storage.base-path` | String | `cloud-storage` | 否 | 本地后端根路径（相对工作目录，如 `cloud-storage`；绝对路径亦可） |

#### 服务发现 / 注册

| 键 | 类型 | 默认值 | 必填 | 说明 |
|----|------|--------|------|------|
| `freeway.cloud.discovery.type` | String | *(空)* | 否 | 发现后端类型。空（或 `local`）= 内置进程内注册表；外部后端（Nacos 等）由适配器 `.primary()` 接入（freeway-ext 未交付）——配了外部值而本地实现仍生效时启动告警一次 |
| `freeway.cloud.registry.type` | String | *(空)* | 否 | 注册后端类型。同上（空/`local` = 进程内注册表） |
| `freeway.cloud.registry.service-id` | String | *(空)* | 否 | 注册的逻辑服务名。空 = 回退 `freeway.app.name`（再回退 `freeway-app`） |
| `freeway.cloud.registry.service-host` | String | *(空)* | 否 | 注册地址主机名。空 = HTTP server 绑定地址（0.0.0.0 等不可路由地址会启动告警，应配置 Pod IP 等外部可达地址） |
| `freeway.cloud.registry.service-scheme` | String | `http` | 否 | 服务注册协议（`http` 或 `https`） |
| `freeway.cloud.registry.service-port` | Integer | *(空)* | 否 | 注册端口。空 = HTTP server 实际监听端口 |
| `freeway.cloud.registry.service-instance-id` | String | *(空)* | 否 | 实例级稳定标识。空 = 派生键 `service-id@host:port` |

#### RPC / 远程调用

| 键 | 类型 | 默认值 | 必填 | 说明 |
|----|------|--------|------|------|
| `freeway.cloud.rpc.connect-timeout` | Long | `3000` | 否 | 连接超时（毫秒） |
| `freeway.cloud.rpc.request-timeout` | Long | `10000` | 否 | 请求超时（毫秒） |
| `freeway.cloud.rpc.trace.enabled` | Boolean | `true` | 否 | 启用 RPC 调用链路追踪 |

#### 弹性 — 重试

| 键 | 类型 | 默认值 | 必填 | 说明 |
|----|------|--------|------|------|
| `freeway.cloud.rpc.retry.max-attempts` | Integer | `3` | 否 | 最大重试次数 |
| `freeway.cloud.rpc.retry.backoff-base` | Long | `100` | 否 | 重试退避基数（毫秒） |
| `freeway.cloud.rpc.retry.backoff-max` | Long | `5000` | 否 | 最大退避时间（毫秒） |

#### 弹性 — 熔断器

| 键 | 类型 | 默认值 | 必填 | 说明 |
|----|------|--------|------|------|
| `freeway.cloud.rpc.circuit-breaker.enabled` | Boolean | `true` | **生产是** | 启用熔断器。生产环境必须启用，防止级联故障 |
| `freeway.cloud.rpc.circuit-breaker.failure-threshold` | Integer | `5` | 否 | 熔断触发阈值（滑动窗口内失败数） |
| `freeway.cloud.rpc.circuit-breaker.failure-window` | Long | `60` | 否 | 滑动窗口时长（秒） |
| `freeway.cloud.rpc.circuit-breaker.open-window` | Long | `30` | 否 | 熔断打开状态持续时长（秒） |

#### 弹性 — 限流

| 键 | 类型 | 默认值 | 必填 | 说明 |
|----|------|--------|------|------|
| `freeway.cloud.rpc.rate-limit.enabled` | Boolean | `false` | 否 | 启用限流。按需开启 |
| `freeway.cloud.rpc.rate-limit.per-second` | Double | `100` | 否 | 每秒最大请求数 |

#### CloudEventBus — 跨节点事件网格

| 键 | 类型 | 默认值 | 必填 | 说明 |
|----|------|--------|------|------|
| `freeway.cloud.events.enabled` | Boolean | `false` | 否 | 启用 CloudEventBus。**默认关闭**，启用前确认网络可达 |
| `freeway.cloud.events.peers` | String | *(空)* | 否 | 对等节点列表 |
| `freeway.cloud.events.subscriptions` | String | *(空)* | 否 | 订阅列表 |
| `freeway.cloud.events.allowed-types` | String | *(空)* | 否 | CLASS 通道反序列化白名单，**空 = 拒绝全部**（deny-by-default，不回退到"放行任意类"） |
| `freeway.cloud.events.allowed-topics` | String | *(空)* | 否 | TOPIC 通道白名单，空 = 放行全部 |
| `freeway.cloud.events.token` | String | *(空)* | 否 | Mesh 握手共享密钥（空 = 无对等认证）。**多节点生产必配**：全节点值一致、经 `FREEWAY_CLOUD_EVENTS_TOKEN` 注入；不一致以 WS `1008` 断开，轮换需滚动重启 |
| `freeway.cloud.events.dedup.enabled` | Boolean | `false` | 否 | 启用事件去重（消耗内存，按需开启） |
| `freeway.cloud.events.dedup.capacity` | Integer | `4096` | 否 | 去重 ID 缓存容量 |
| `freeway.cloud.events.connect-timeout-ms` | Long | `3000` | 否 | 出站拨号 socket 连接超时（毫秒） |
| `freeway.cloud.events.handshake-timeout-ms` | Long | `10000` | 否 | 握手看门狗：连接建立后等待 hello/ack 的超时（毫秒） |
| `freeway.cloud.events.backoff-base-ms` | Long | `1000` | 否 | 断线重连退避基数（毫秒，指数退避） |
| `freeway.cloud.events.backoff-max-ms` | Long | `30000` | 否 | 断线重连退避上限（毫秒） |

#### RPC / TLS

| 键 | 类型 | 默认值 | 必填 | 说明 |
|----|------|--------|------|------|
| `freeway.cloud.rpc.tls.key-store` | String | *(空)* | 否 | RPC 客户端密钥库路径（mTLS 场景） |
| `freeway.cloud.rpc.tls.key-store-password` | String | *(空)* | 否 | RPC 客户端密钥库密码 |
| `freeway.cloud.rpc.tls.trust-store` | String | *(空)* | 否 | RPC 客户端信任库路径 |
| `freeway.cloud.rpc.tls.trust-store-password` | String | *(空)* | 否 | RPC 客户端信任库密码 |

#### Auth 传播

| 键 | 类型 | 默认值 | 必填 | 说明 |
|----|------|--------|------|------|
| `freeway.cloud.auth.extract.enabled` | Boolean | `false` | 否 | 启用 inbound `x-principal` 提取（仅信任服务网内部） |

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
      "events": {
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
- **PlantUML**：`PlantumlOptions` 控制输出格式

---

## 七、IoC — 容器

IoC 容器不提供外部化配置键。所有配置通过编程式 API 完成：

- **绑定**：`binder.bind(X.class).to(Y.class)` / `.to(c -> ...)`
- **作用域**：`bind().scope(SINGLETON | PROTOTYPE | THREAD)`
- **`.primary()`**：引擎/池/方言选择 — 默认实现绑定无 `.primary()`，扩展模块绑定替代实现时带 `.primary()`，容器自动解析
- **注入**：`@Inject`、`@Symbol`（严格查找）、`@Value("${key:default}")`（表达式展开）
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

支持的后缀：`ms`（毫秒）、`s`（秒）、`m`（分钟）、`h`（小时）

---

## 环境变量映射

默认前缀 `FREEWAY_`，下划线转点号：
- `freeway.http.port` → `FREEWAY_HTTP_PORT`
- `freeway.db.url` → `FREEWAY_DB_URL`
- `freeway.log.level` → `FREEWAY_LOG_LEVEL`

带连字符的键（如 `max-size`）不支持环境变量，需用 `-D` 系统属性。

例外（读自 JVM 系统属性，不参与上述级联）：`freeway.cloud.secret.file`、
`freeway.cloud.secret.keys` —— 两者只能 `-D` 设置，否则静默无效。

自定义前缀 `freeway.env.prefix=APP_`：`APP_SERVER_PORT` → `server.port`（透传）
