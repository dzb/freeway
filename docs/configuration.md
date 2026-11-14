# Freeway Configuration Reference

> All configuration keys are dot-separated hierarchical keys, unified under the `freeway.*` namespace.
> Configuration source precedence (low → high): `application.properties` → `application.json` → `application-{profile}.properties` → `application-{profile}.json` → **working-directory/`freeway.config.file` override files** (with the same names and order; the filesystem as a whole outranks the classpath baseline and is hot-reloadable) → **module-contributed sources** (for example a cloud secret store, `order()=15`) → environment variables (`FREEWAY_*`, `order()=10`) → **JVM system properties** (`-Dkey=value` before the main class starts, keys verbatim, `order()=5`) → CLI arguments (`--key=value`, `order()=0`).
> Precedence is determined by each source's declared `SymbolProvider.order()` (ascending lookup, first match wins), independent of module installation order.
> See the configuration cascade chapter of [architecture.md](architecture.md).

---

## Configuration classification rules

Each key's **class** is stated in the tables below. Classification is not a matter of taste but the result of four questions asked in order — **one ruler measures every key** — and a new key need only answer them in the same order:

| # | Criterion | Resulting class |
|---|------|-----------|
| 1 | No reasonable default, and startup fails when it is missing? | **required** (unconditional) / **required·conditional** (required only when a feature is enabled) |
| 2 | Has a default, but one that holds only in development/on the local machine? | **decision** — change per environment when deploying |
| 3 | The default is the recommended posture, written only to turn a feature off? | **posture** — the `.enabled` family and presence primary keys |
| 4 | Is the value a number/duration/size/path whose default is the framework-recommended value? | **tuning** — leave it alone |

Two further classes are **not "decisions"** — do not mix them with the above:

- **declaration** (`freeway.cloud.{secret,storage,discovery,registry}.type`): expresses only "which backend I intend to use"; it **does not select an implementation** (selection = a `.primary()` binding, an `@Local` marker, which adapter module is installed). When the local implementation is still in use and the declaration is non-empty, startup warns once.
- **mechanism** (`freeway.config.file`, `freeway.env.prefix`, `freeway.cloud.secret.file/.keys`): these configure the configuration system itself, and writing them into any configuration file has no effect. The first two may come from `-D` or `FREEWAY_CONFIG_FILE`/`FREEWAY_ENV_PREFIX`; the latter two **honour `-D` only** — they are read through the symbol resolution chain, and going through the cascade once would recurse.

A **shape suffix** describes where the value comes from (it determines whether the value can be "hidden"):

| Suffix | Meaning | Example |
|------|------|------|
| `·auto` | The value must be **derived**; it cannot be written literally | `log.file=auto` (the path embeds the runtime's `app.name`), `registry.service-scheme=auto` (follows whether the HTTP server has TLS enabled), `registry.service-host=auto` (POD_IP → the first routable local address), `registry.shutdown-drain=auto` (answered by the registry backend) |
| `·presence` | Only once the primary key appears do the cluster's sub-keys become meaningful | `ssl.key-store` (the path is what enables HTTPS), `event.peers`, `rpc.tls.key-store` |
| `·sentinel` | Empty/`0` = defer to the platform, the JDK or the runtime | `dialect=""` (inferred from the JDBC URL), `registry.service-port` (empty = the actual listening port), `max-connections=0` (OS default) |
| `·aggregate switch` | One key governs a whole cluster of tuning keys | `rpc.resilience=auto\|off` (when `off`, all `retry.*`/`circuit-breaker.*`/`rate-limit.*` are ignored) |

**Classes you do not need to read**: for the four classes `posture` / `tuning` / `declaration` / `mechanism`, the default is the recommended value; only `required` and `decision` need an answer per deployment. A service that does not involve HTTPS and does not face browsers needs only 2 of them:

| Module | Keys you must decide | Notes |
|------|-------------|------|
| **DB** | `freeway.db.url`, `freeway.db.username` | No default values, checked at startup; the password comes from `FREEWAY_DB_PASSWORD` |
| **HTTP** | `freeway.http.server.host` / `.port` | Default `127.0.0.1:8080`; in a container usually changed to `0.0.0.0` |
| **HTTP** | `freeway.http.ssl.key-store` + `-password` | When HTTPS is enabled in production (the keystore path is what enables it; without it, plaintext) |
| **HTTP** | `freeway.http.cors.allowed-origins` | The default `*` is a development posture; write the real domain when deploying |
| **Cloud** | `freeway.cloud.event.peers` + `.token` | Both are needed when the event mesh is enabled (non-empty peers is the switch; the token must be identical on all nodes) |
| **Cloud** | `freeway.cloud.rpc.tls.key-store` + `-password` | When outbound RPC uses mTLS (the path is what enables it) |
| **Cloud** | `freeway.cloud.registry.service-host` | `auto` is usually enough in a container; a multi-NIC host must be named explicitly |
| **Cloud** | `freeway.cloud.secret.file` / `.keys` | The secret backend's own configuration, **readable only via `-D`** (environment variables do not work; see §V · Secrets) |

**Why the tuning class needs no "master switch"**: each cluster already has one key governing it, and adding another would only state "off" in different words — `rpc.resilience` governs the nine resilience keys, the `event.enabled` presence governs the four `event.*-ms` transport timeouts (which are meaningless when no mesh exists), the `ssl.enabled` presence governs the whole TLS cluster, and tuning keys themselves say "use the platform default" (`0` = OS/JDK default, empty = JDK default).

**Why not turn every tuning key into `auto`**: `auto` holds only when the **value must be derived** (6 keys carry the `·auto` suffix: log path/format/colour, registry scheme/host, shutdown drain). Adding `auto` to a constant default yields only a second way of stating the same rule — one more branch, one more set of docs, one more test, and no information gained. **Logging** is the model: `log.file=auto` makes the whole `logs/{app.name}.log` + dual rotation + GZIP + 30-day retention cluster invisible, while constant keys such as `max-size` stay writable, merely absent from the samples.

### How configuration files are organized

**One file by default**: `application.properties` (or `.json`) as the baseline plus `application-{profile}.*` as the environment axis. The reason: only the few keys in the table above need a human answer, and the production sample activates just 22 in total; profile is already the orthogonal axis for "partitioning by environment".

Splitting by module is an **optional convention**, valuable only when "multiple teams/multiple ConfigMaps each own their configuration" — mechanically it requires no code change, since `freeway.config.file` already supports multiple files (comma-separated, order is precedence, all hot-reloaded). Three rules when splitting:

1. Files and namespaces are 1:1: `config/http.properties` holds only `freeway.http.*`;
2. **A key has exactly one home** — the same key appearing in two override files warns and names both files (the later one wins), but do not rely on it;
3. Write the order explicitly in `FREEWAY_CONFIG_FILE` / `-Dfreeway.config.file`; never guess it from file names.

Two boundaries: the extra files listed by `freeway.config.file` **do not derive profile variants** (module × profile would double the file count; profile increments still belong in `application-{profile}.*`); the framework **does not auto-load** per-module default files — defaults have exactly one source, the constants in each module's `*ConfigKeys`.

---

## Hot Reload: What Changes, What Does Not

The file tier is watched and swapped in place; the `SymbolSource` reads the
live snapshot on every lookup. That is the **whole** hot-reload mechanism — it
does not re-run module binding, rebuild constructed objects, or re-inject
singletons. Use the table as the deployment contract:

| Surface | Hot reload |
|---|---|
| Values read through `SymbolSource` on each lookup | **live** — the source resolves the new snapshot |
| `@Symbol` / `@Value` constructor or field injection | **startup** — captured when the object is realized |
| HTTP host/port, TLS enablement, routes, filters, static mounts, CORS policy | **startup** |
| DB URL/credentials, pool sizing, schema posture, migrations | **startup** |
| Cloud discovery/registry, RPC settings, resilience policy, mesh peers, storage | **startup** |
| `freeway.profile` and the active profile set | **startup-static** by design |
| HTTP TLS key/trust **material** | **live** when `freeway.http.ssl.reload-interval` is non-zero; the interval itself is startup-captured |
| Cloud outbound RPC TLS **material** | **live** when `freeway.cloud.rpc.tls.reload-interval` is set; the interval itself is startup-captured |
| Logging configuration | **startup** — the logging bootstrap runs before the config cascade and is not part of file-tier reload |
| Application code that resolves a symbol per call | **live**, because it reads the same chain |

To change a startup-only key, restart the process. The explicit TLS exceptions
exist because certificate rotation cannot wait for a restart; they re-read the
files named by the already-resolved config, not the config keys.

## Module overview

| Module | Namespace | Notes |
|------|----------|------|
| **Boot** | `freeway.profile`, `freeway.config.file`, `freeway.env.prefix` | Runtime startup and the configuration cascade |
| **Commons** | `freeway.log.*`, `freeway.env.prefix` | Logging system |
| **HTTP** | `freeway.http.*` | Web server, routing, SSL |
| **DB** | `freeway.db.*` | Database connections, pool, schema, migrations |
| **Cloud** | `freeway.cloud.*` | Cloud native — discovery, RPC, resilience, events, storage, secrets |
| **Flow** | No external configuration | Workflow engine, programmatic configuration only |
| **IoC** | No external configuration | Container, bindings, scopes; programmatic only |

---

## I. Boot — Runtime Startup

### Configuration keys

| Key | Class | Type | Default | Required | Meaning |
|----|------|------|--------|------|------|
| `freeway.profile` | decision | String | *(none)* | no | Active profile(s); comma-separated multiple values are supported. If unset, no profile variant files are loaded. Use `dev` in development and `prod` in production |
| `freeway.config.file` | mechanism | String | *(empty)* | no | Extra configuration file paths, comma-separated. Participates in file-level hot reload |
| `freeway.env.prefix` | mechanism | String | `FREEWAY_` | no | Environment variable prefix; with a custom prefix, `APP_SERVER_PORT` → `server.port` (passed through) |

`freeway.config.file` and `freeway.env.prefix` are **bootstrap-only keys**:
they configure the configuration system itself, so they can come only from `-D<key>` or
`FREEWAY_<key>` (for example `FREEWAY_ENV_PREFIX`, `FREEWAY_CONFIG_FILE`).
Writing them into `application.properties`/`application.json` (classpath or working directory) has no effect,
and neither does passing them as CLI arguments (`--freeway.config.file=...`) — startup prints a
WARN naming the source file or "command-line arguments" rather than silently ignoring them.
The `FREEWAY_` prefix for these two keys is fixed (otherwise `env.prefix` would have to be
read through the mapping configured by `env.prefix`); `freeway.profile` is not a bootstrap
key and goes through the normal cascade.

Only the **baseline layer** activates a profile: classpath and working-directory
`application.properties`/`application.json`, mapped environment variables (`FREEWAY_PROFILE`),
and CLI arguments (`--freeway.profile=dev`). A `freeway.profile` rewritten inside a profile
variant file is stripped, so that `profiles()` and the resolved value cannot contradict each
other; the other two channels write ordinary configuration values only and **do not activate
profiles**: `-Dfreeway.profile=dev` (JVM system property) and `freeway.config.file` extra files.

### CLI key rules

- Key names are parsed **literally**, with no prefix added: `--app.name=foo` → `app.name=foo`,
  `--mode=prod` → `mode=prod` (application keys are not absorbed into the framework namespace)
- Framework keys must be written in full: `--freeway.profile=dev`, `--freeway.http.server.port=8080`

### Example

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

## II. Commons — logging

### Where log configuration lives

Log keys have two file homes (`-D` system properties and environment variables
outrank both files; the dedicated file is the more specific declaration and
outranks the application files; every key in this chapter is scoped to the
`freeway.log.*` namespace):

1. **Dedicated file** `freeway-logging.properties` (classpath root) — the
   log-specific declaration and the higher-ranked file. The pre-1.5.2 name
   `freeway-log.properties` is **no longer read**: if it is still on the
   classpath, startup prints a one-line rename notice instead of silently
   ignoring it.
2. **Application config** `application.properties` / `application.json`
   (classpath root and the active profile variants; working-directory files and
   hot reload never participate in logging) — only `freeway.log.*` keys are
   taken.

**Backend selection** is decided at class-load time by `freeway.log.system`.
The default `freeway` keeps Freeway's JUL provider even when Logback/Log4j is
on the classpath (startup names the ignored provider and the switch); `auto`
restores "external provider first"; `logback` / `log4j2` / `simple`, or a
fully-qualified provider class, selects one explicitly — if it is not on the
classpath, startup fails and names the missing dependency. `-Dslf4j.provider`
is SLF4J's own escape hatch and takes precedence. Every startup prints one
report: `[Freeway] logging: freeway-logging (JUL) | provider=... | selected by ...`.

With a non-JUL backend selected, Freeway pins the SLF4J binding and **leaves
`java.util.logging` untouched**: it attaches no JUL handlers, formatters or
files. JDK-internal JUL records (for example HttpClient) stay on JUL's own
configuration; an application that wants them in its backend adds the standard
`jul-to-slf4j` bridge itself (dependency plus the usual `LogManager` reset).
Freeway's JUL enhancement is configured only for its own JUL backend.

Three boundaries:

- **Five keys cannot live in files**: `freeway.log.system`, `freeway.log.color`,
  `freeway.log.mdc`, `freeway.log.mdc.priority` and
  `freeway.log.caller-info` are read at **class-load time** (before any file is
  parsed) and only honour `-D` and environment variables. A declaration in any
  config **file** never takes effect, and startup prints a WARN naming the key,
  its value, its source and the available channels.
- **Per-logger levels** (`<logger>.level`) are supported only in the dedicated
  file: in the application config they cannot be distinguished from ordinary
  dotted keys, so they would turn arbitrary application keys into loggers.
- The application-file side is the main cascade's **classpath baseline**
  (including the profile selected through `-D`/env/classpath files);
  working-directory declarations and hot reload never reach logging. Therefore
  **CLI arguments** (`--freeway.log.level=DEBUG`) and `freeway.log.*` keys in
  working-directory override files do not affect loggers; startup warns for each
  such key with the three working channels (`-D`, environment, classpath file).

Three **process-level keys** (not under `freeway.*`, but read by the framework):

| Key | Class | Type | Default | Meaning |
|-----|-------|------|---------|---------|
| `app.name` (JVM `-D`) | mechanism | String | `freeway` | Base name of the default log file `logs/{app.name}.log`. Unrelated to `freeway.app.name` (the cloud service-name fallback) |
| `slf4j.provider` (JVM `-D`) | mechanism | String | decided by `freeway.log.system` | JVM-level escape hatch for the SLF4J provider; when set it **outranks** `freeway.log.system`, and startup explains the conflict |
| `NO_COLOR` (env) | mechanism | presence | unset | Disables ANSI colour (no-color.org) |

#### Global logging

| Key | Class | Type | Default | Required | Meaning |
|-----|-------|------|---------|----------|---------|
| `freeway.log.system` | mechanism·-D | String | `freeway` | no | SLF4J backend: `freeway` (default, Freeway's JUL backend), `auto` (external provider first), `logback`/`log4j2`/`simple`, or a provider class name. Read at class-load time; ignored in files. A named backend that is absent from the classpath fails startup and names the missing dependency |
| `freeway.log.level` | decision | String | `INFO` | no | Root log level. SLF4J names (TRACE/DEBUG/INFO/WARN/ERROR/OFF) or JUL names (FINEST/FINE/INFO/WARNING/SEVERE); output always displays the SLF4J name, including JUL-origin records |
| `freeway.log.format` | tuning·auto | String | `auto` | no | Formatter mode: `auto` (Freeway formatter + ANSI) or `simple` (JUL `SimpleFormatter`) |
| `freeway.log.color` | tuning·auto·-D | String | `auto` | no | Colour mode: `auto` (follows format), `always`, `never`. Honours `NO_COLOR` |
| `freeway.log.caller-info` | tuning·-D | Boolean | `true` | no | Resolve source class/method per record. Set `false` for higher throughput on very high-volume logging |
| `freeway.log.mdc` | tuning·-D | Boolean | `true` | no | Include the MDC block in output. MDC is the per-thread context map used for tracing/request correlation |
| `freeway.log.mdc.priority` | tuning·-D | String | unset (alphabetical) | no | Display order of MDC keys (comma-separated); unlisted keys stay alphabetical. The framework presets no key names — declare the keys you want to highlight (`traceId`, `requestId`, ...) |

#### Console output

| Key | Class | Type | Default | Required | Meaning |
|-----|-------|------|---------|----------|---------|
| `freeway.log.console.enabled` | posture | Boolean | `true` | no | Enable the console handler |
| `freeway.log.console.level` | tuning | String | *(inherits root)* | no | Console handler level; empty inherits the global level |

#### Default file logging

| Key | Class | Type | Default | Required | Meaning |
|-----|-------|------|---------|----------|---------|
| `freeway.log.file` | tuning·auto | String | `auto` | no | File mode: `auto` (managed `logs/{app.name}.log`) or `off` (disabled) |
| `freeway.log.file.max-size` | tuning | Long | `104857600` (100 MB) | no | Size-rotation threshold in bytes |
| `freeway.log.file.max-history` | tuning | Integer | `30` | no | Days to keep archives |
| `freeway.log.file.compress` | tuning | Boolean | `true` | no | GZIP archives after rotation |
| `freeway.log.file.flush-interval` | tuning | Long | `250` | no | Background flush interval in milliseconds; `0` flushes every record (safest, lowest throughput) |

#### Multi-file logging

| Key | Class | Type | Default | Required | Meaning |
|-----|-------|------|---------|----------|---------|
| `freeway.log.files` | posture·presence | String | *(empty)* | no | Comma-separated named files (for example `biz,audit`) |
| `freeway.log.file.<name>.path` | required·conditional | String | *(required)* | yes | Path of the named file (required for a named file) |
| `freeway.log.file.<name>.logger` | decision | String | *(root)* | no | Logger bound to the named file |
| `freeway.log.file.<name>.level` | tuning | String | *(inherits parent)* | no | Level of the named file |
| `freeway.log.file.<name>.max-size` | tuning | Long | `104857600` | no | Size-rotation threshold of the named file |
| `freeway.log.file.<name>.max-history` | tuning | Integer | `30` | no | Days to keep the named file's archives |
| `freeway.log.file.<name>.compress` | tuning | Boolean | `true` | no | GZIP the named file's archives |
| `freeway.log.file.<name>.flush-interval` | tuning | Long | `250` | no | Flush interval of the named file |

#### Per-package/class levels

| Key | Class | Type | Default | Required | Meaning |
|-----|-------|------|---------|----------|---------|
| `<logger-name>.level` | tuning | String | *(inherits parent)* | no | Level for a package/class, for example `com.zaxxer.hikari.level=WARNING` |

### Example

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

> `color`, `caller-info`, `mdc` and `mdc.priority` are absent on purpose: they
> only honour `-D` and environment variables (for example
> `-Dfreeway.log.mdc.priority=code,traceId`); a file declaration is silently
> ineffective (startup warns). `file.max-size` and its siblings belong under
> `freeway.log.file.*`; writing a one-level `"max-size"` would create the
> nonexistent key `freeway.log.max-size`.

---

## III. HTTP — Web Server

### Configuration keys

**Classification** (rules in "Configuration classification rules" at the top): decision = `server.host`/`server.port`,
`server.shutdown-grace`, `cors.allowed-origins`, plus `ssl.key-store` + `-password` and `ssl.trust-store` + `-password`
when HTTPS/mTLS is enabled; posture = `ssl.key-store` (presence) and each feature's `.enabled` (`compression`,
`access-log`, `cors`, `health`); the rest is **tuning** — the default is the recommended value, `0` means the OS/JDK
default or no limit, and empty means the JDK default.

#### Server

| Key | Class | Type | Default | Required | Meaning |
|----|------|------|--------|------|------|
| `freeway.http.server.host` | decision | String | `127.0.0.1` | no | Bind address. `127.0.0.1` in development, `0.0.0.0` in production (forwarded by a reverse proxy such as Nginx) |
| `freeway.http.server.port` | decision | Integer | `8080` | no | Listening port (`0` = assigned by the system) |
| `freeway.http.server.backlog` | tuning | Integer | `0` | no | Accept queue size (`0` = system default) |
| `freeway.http.server.shutdown-grace` | decision | Duration | `2s` | no | Time to wait for in-flight requests when closing. `2s` in development, `30s` in production (rolling deployments) |
| `freeway.http.server.read-timeout` | tuning | Duration | `30s` | no | Socket read idle timeout (`0` = disabled) |
| `freeway.http.server.write-timeout` | tuning | Duration | `30s` | no | Timeout for a single write operation (`0` = disabled) |
| `freeway.http.server.max-connections` | tuning | Integer | `0` | no | Maximum number of concurrent connections (`0` = unlimited) |
| `freeway.http.server.receive-buffer-size` | tuning | Integer | `0` | no | SO_RCVBUF (`0` = OS default) |
| `freeway.http.server.send-buffer-size` | tuning | Integer | `0` | no | SO_SNDBUF (`0` = OS default) |
| `freeway.http.server.max-header-bytes` | tuning | Integer | `16384` | no | Inbound request header limit (bytes); the HTTP/1.1 header block and the HTTP/2 header block/field table/SETTINGS share the same number, so changing it does not fork the two protocols |

#### Compression

| Key | Class | Type | Default | Required | Meaning |
|----|------|------|--------|------|------|
| `freeway.http.compression.enabled` | posture | Boolean | `true` | no | Enable gzip response compression |
| `freeway.http.compression.min-size` | tuning | Integer | `256` | no | Minimum response body size to compress (bytes) |

#### Access log

| Key | Class | Type | Default | Required | Meaning |
|----|------|------|--------|------|------|
| `freeway.http.access-log.enabled` | posture | Boolean | `false` | no | Enable the text access log (stdout prints method path status elapsed-ms ip ua); one line per exchange, including CORS preflights and health probes. Custom destination: turn this key off and contribute your own `EventSubscriber<HttpExchangeEvent>` |

#### Request scope

| Key | Class | Type | Default | Required | Meaning |
|----|------|------|--------|------|------|
| `freeway.http.request-scope.enabled` | posture | Boolean | `false` | no | Open a container value scope per HTTP request (`ScopeFilter`, order -40, after CORS/health and before application filters), so `Scope.BOUNDARY` services and scoped cache values live per request and are cleaned up when the request ends; not propagated to child threads — asynchronous work needs `ContextExecutor` |

#### CORS

| Key | Class | Type | Default | Required | Meaning |
|----|------|------|--------|------|------|
| `freeway.http.cors.enabled` | posture | Boolean | `true` | no | Enable CORS filtering |
| `freeway.http.cors.allowed-origins` | decision | List(String) | `*` | no | Allowed origins (comma-separated). Production must specify concrete domains; `*` conflicts with `allow-credentials=true`, and startup fails |
| `freeway.http.cors.allowed-methods` | tuning | List(String) | `GET, POST, PUT, DELETE, PATCH, OPTIONS` | no | Allowed methods |
| `freeway.http.cors.allowed-headers` | tuning | List(String) | `Content-Type, Authorization` | no | Allowed request headers |
| `freeway.http.cors.exposed-headers` | tuning | List(String) | *(empty)* | no | Exposed response headers |
| `freeway.http.cors.max-age` | tuning | Integer | `3600` | no | Preflight cache duration (seconds) |
| `freeway.http.cors.allow-credentials` | tuning | Boolean | `false` | no | Allow credentials |

#### Health checks

| Key | Class | Type | Default | Required | Meaning |
|----|------|------|--------|------|------|
| `freeway.http.health.enabled` | posture | Boolean | `true` | no | Enable health endpoints |
| `freeway.http.health.path` | tuning | String | `/healthz` | no | Health check path |

> The cloud probes are not in the table above: installing `HealthModule` contributes, at fixed paths,
> `GET /health/live` (process liveness) and `GET /health/ready` (dependency readiness aggregation;
> 200 when everything is healthy, otherwise 503), and the paths are **not configurable**; they are
> a separate set of endpoints from `/healthz` (`freeway.http.health.path`), so do not configure
> them to the same path. See `docs/design/cloud.md` §5.6 for details.

#### Request body

| Key | Class | Type | Default | Required | Meaning |
|----|------|------|--------|------|------|
| `freeway.http.max-body-size` | tuning | Long | `10485760` (10 MB) | no | Maximum request body size (bytes) |

#### SSL / HTTPS

| Key | Class | Type | Default | Required | Meaning |
|----|------|------|--------|------|------|
| `freeway.http.ssl.key-store` | posture·presence | String | *(empty)* | **yes** in production | **presence primary key** — keystore path (PKCS12 or JKS); non-empty means HTTPS is enabled |
| `freeway.http.ssl.enabled` | posture | Boolean | *(unset)* | no | Explicit master switch; **if set, it wins**: `true` enables (in which case key-store is required), `false` = master off (suppresses even a configured keystore, back to plaintext). When unset, the presence rule applies |
| `freeway.http.ssl.key-store-password` | required·conditional | String | *(empty)* | when TLS is enabled | Keystore password (not pre-checked in code; a missing value fails at the keystore-loading stage). The key name contains hyphens and so does the environment variable name (`FREEWAY_HTTP_SSL_KEY-STORE-PASSWORD`): inject it with `-D`, `env 'NAME=...'` or a container `-e`; the shell's `export` does not support names containing `-` |
| `freeway.http.ssl.key-store-type` | tuning | String | `PKCS12` | no | Keystore type |
| `freeway.http.ssl.http2` | tuning | Boolean | `true` | no | Enable HTTP/2 over TLS via ALPN |
| `freeway.http.ssl.trust-store` | decision | String | *(empty)* | no | Optional truststore path (mTLS scenarios) |
| `freeway.http.ssl.trust-store-password` | decision | String | *(empty)* | no | Truststore password |
| `freeway.http.ssl.trust-store-type` | tuning | String | `PKCS12` | no | Truststore type |
| `freeway.http.ssl.client-auth` | tuning | Boolean | `false` | no | Require client certificates (mTLS) |
| `freeway.http.ssl.protocols` | tuning | String | *(empty)* | no | Comma-separated TLS protocol versions |
| `freeway.http.ssl.ciphers` | tuning | String | *(empty)* | no | Comma-separated TLS cipher suites |
| `freeway.http.ssl.sni-directory` | tuning | String | *(empty)* | no | Per-hostname keystore directory for SNI |
| `freeway.http.ssl.reload-interval` | tuning | Duration | `0` | no | Certificate hot-reload interval (file watching triggers immediately, polling is the fallback; `0` = disable hot reload) |

#### HTTP/2

> These two keys belong to the built-in engine: `HttpModule` reads them and wires them into `FreewayHttpEngine.Wiring`;
> they no longer enter `HttpServerConfig`. If a third-party engine is active and the values were tuned, startup WARNs
> and names the owner.

| Key | Class | Type | Default | Required | Meaning |
|----|------|------|--------|------|------|
| `freeway.http.h2.reset-burst-limit` | tuning | Integer | `200` | no | Inbound RST burst circuit breaker: when more than this number of RSTs that cancel a stream without a response occur within the window, send GOAWAY(ENHANCE_YOUR_CALM) and tear down the connection (`0` = the whole guard is off) |
| `freeway.http.h2.reset-window` | tuning | Duration | `10s` | no | Sliding window for counting RST bursts. **It is a pair with the previous row**: when `reset-burst-limit=0` this key is meaningless (the code returns immediately), so the two are not two independent decisions |

### Example

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

## IV. DB — Database

### Configuration keys

#### Connection

| Key | Class | Type | Default | Required | Meaning |
|----|------|------|--------|------|------|
| `freeway.db.url` | required | String | *(none)* | **yes** | JDBC connection URL; no default, checked at startup |
| `freeway.db.username` | required | String | *(none)* | **yes** | Database username; no default, checked at startup |
| `freeway.db.password` | decision | String | *(empty)* | no | Database password. In production inject it through the `FREEWAY_DB_PASSWORD` environment variable |
| `freeway.db.dialect` | tuning·sentinel | String | *(empty)* | no | SQL dialect (`postgresql`/`mysql`/`sqlite`/`h2`). Empty auto-detects from the JDBC URL; multiple data sources must specify it explicitly |
| `freeway.db.query-timeout` | tuning | Duration | `15s` | no | Statement query timeout (`0` = no timeout). Report-style queries may raise it |

#### Connection pool

| Key | Class | Type | Default | Required | Meaning |
|----|------|------|--------|------|------|
| `freeway.db.pool.max-size` | tuning | Integer | `10` | no | Maximum connections in the pool. `4` in development; raise it to match concurrency in production |
| `freeway.db.pool.min-idle` | tuning | Integer | `2` | no | Minimum idle connections |
| `freeway.db.pool.connection-timeout` | tuning | Duration | `10s` | no | Maximum wait to acquire a connection from the pool |
| `freeway.db.pool.max-lifetime` | tuning | Duration | `30m` | no | Maximum connection lifetime |
| `freeway.db.pool.max-idle-time` | tuning | Duration | `10m` | no | Maximum connection idle time |
| `freeway.db.pool.clean-interval` | tuning | Duration | `2m` | no | Idle-eviction cleanup interval |
| `freeway.db.pool.health-check-query` | tuning | String | *(empty)* | no | Health check SQL (when empty, JDBC `isValid()` is used). `SELECT 1` validates explicitly |
| `freeway.db.pool.health-check-timeout` | tuning | Duration | `5s` | no | Health check query timeout |

#### Schema

| Key | Class | Type | Default | Required | Meaning |
|----|------|------|--------|------|------|
| `freeway.db.schema.mode` | posture | String | `auto` | no | Schema DDL posture: `auto` converges at startup (zero friction in development); `validate` runs migrations and then compares entities with the database, failing startup on a mismatch (production); `off` skips. The retired `freeway.db.schema.auto` **has no effect**: setting only the old key **fails startup** and names the new key plus true→auto/false→off (otherwise a deployment originally on `false` in production would silently fall back to the default `auto`); when both are set, the new key wins, the old value is ignored and a WARN is printed. **Usable on MySQL/MariaDB**: this path forbids DDL only **inside a user transaction** (where DDL would implicitly commit), and automatic startup migrations are not subject to that restriction — **unlike** the dialect limitation of `freeway.db.migration.*`, see the next row |
| `freeway.db.schema.groups` | tuning | String | *(empty)* | no | Comma-separated schema group filter. Empty = all groups |

#### Migrations

| Key | Class | Type | Default | Required | Meaning |
|----|------|------|--------|------|------|
| `freeway.db.migration.enabled` | posture | Boolean | `true` | no | Enable SQL migration execution. **Must be `true` in production**. ⚠️ **Dialect-dependent**: on dialects without transactional DDL (MySQL/MariaDB), **a migration file containing DDL throws `SqlException` during startup** — the DDL commits implicitly while the verification row is lost, so re-running that DDL on the next startup fails. On MySQL, split DDL into separate, idempotent migrations (`IF NOT EXISTS`), or switch to `freeway.db.schema.mode=auto` so entities drive the DDL (that path is not subject to this restriction). **Note this differs from the `freeway.db.schema.*` restriction**: schema refuses only when DDL runs **inside a user transaction**, and works normally on MySQL |
| `freeway.db.migration.path` | tuning | String | `db/migration/` | no | Classpath directory containing migration SQL files |
| `freeway.db.migration.table` | tuning | String | `_migrations` | no | Migration tracking table name |
| `freeway.db.migration.lock-ttl` | tuning | Duration | *(runner default 1h)* | no | Lock row lifetime (ISO-8601, for example `PT1H`). Empty uses the runner default. `0` or a negative value disables expired-lock takeover. Recommended for multi-instance deployments |

### Example

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

## V. Cloud — Cloud Native

### Configuration keys

**Classification** (rules in "Configuration classification rules" at the top): decision = `event.peers` + `event.token`,
`rpc.tls.key-store` + `-password` (when using mTLS),
`registry.service-host` (`auto` is usually enough in a container; a multi-NIC host must be named explicitly); posture = each
`.enabled`, presence primary keys, and the three `auto` keys (`registry.service-scheme` /
`.service-host` / `registry.shutdown-drain`); declaration = the four `*.type`; mechanism =
`secret.file`/`secret.keys` (`-D` only); the rest is **tuning**, and each cluster already has a key governing it
(`rpc.resilience=auto|off`, the `event.enabled` presence).

**Two kinds of key, do not confuse them** (especially for cloud-backend keys):

| Category | Example | Role |
|---|---|---|
| **Selection key** | a `.primary()` binding, an `@Local` marker, the adapter module itself | **Actually decides which implementation is used**: during container resolution the primary binding wins and the local default steps aside |
| **Declaration key** | `freeway.cloud.discovery.type` / `registry.type` / `secret.type` / `storage.type` | **Expresses intent only**: "I intend to use Nacos / Consul / S3 / Vault". The framework **does not use it to select an implementation** (no reflective class-name loading and no classpath scanning); an adapter **may** read it for validation/warnings (the Consul adapter treats it as a pure intent declaration that does not participate in selection), and when "an external backend is declared but the local implementation is still the active binding" it triggers a one-time startup warning (`BackendTypeGuard`), so a declaration is not mistaken for effect |

So: to switch backends, write an adapter module and `.primary()` it; `*.type` is a
declaration and self-check for humans and adapters — a wrong value (or a missing adapter) does not
silently take effect, but warns once at startup.

#### Secrets

| Key | Class | Type | Default | Required | Meaning |
|----|------|------|--------|------|------|
| `freeway.cloud.secret.type` | declaration | String | *(empty)* | no | Secret backend type. Empty (or `local`) = the built-in env/file implementation; external backends (Vault etc.) are plugged in by an adapter via `.primary()` (not yet delivered in freeway-ext) — if an external value is configured while the local implementation is still active, startup warns once |
| `freeway.cloud.secret.file` | mechanism·-D | String | `application-secrets.properties` | no | Secret file path (key=value format). **Takes effect only via `-D` system properties**: the secret provider participates in symbol resolution and cannot read its own configuration through that chain, so writing it into a configuration file or an environment variable has no effect. When the file is replaced (size/mtime change) the change takes effect **without a restart** (throttled to at most 1s); a failed read or a momentarily missing file keeps the loaded values and warns |
| `freeway.cloud.secret.keys` | mechanism·-D | String | *(empty)* | no | Allowlist of symbol names that may be resolved from the secret store (comma-separated). **Takes effect only via `-D` system properties** (same as above). Leaving it empty is the sharp default of "look up the environment variable for any symbol name", and startup prints a WARN |

#### Object storage

| Key | Class | Type | Default | Required | Meaning |
|----|------|------|--------|------|------|
| `freeway.cloud.storage.type` | declaration | String | *(empty)* | no | Storage backend type. Empty (or `local`) = the built-in local filesystem implementation; external backends (S3 etc.) are plugged in by an adapter via `.primary()` (not yet delivered in freeway-ext) — if an external value is configured while the local implementation is still active, startup warns once |
| `freeway.cloud.storage.base-path` | tuning | String | `cloud-storage` | no | Root path of the local backend (relative to the working directory, for example `cloud-storage`; absolute paths also work) |

#### Service discovery / registry

| Key | Class | Type | Default | Required | Meaning |
|----|------|------|--------|------|------|
| `freeway.cloud.discovery.type` | declaration | String | *(empty)* | no | Discovery backend type. Empty (or `local`) = the built-in in-process registry; external backends are plugged in by an adapter via `.primary()` (Consul is delivered in freeway-ext; Nacos etc. are not) — if an external value is configured while the local implementation is still active, startup warns once |
| `freeway.cloud.discovery.local-zone` | tuning·sentinel | String | *(empty)* | no | This process's zone. When set and at least one instance declares a `zone` metadata, the default balancer runs weighted round-robin over local-zone candidates; if none match it falls back to the full list. Empty = no zone preference. `canary` stays a weight/custom-policy input; the default never routes all traffic to a canary just because one exists |
| `freeway.cloud.registry.type` | declaration | String | *(empty)* | no | Registry backend type. Same as above (empty/`local` = the in-process registry; Consul is delivered in freeway-ext) |
| `freeway.cloud.registry.service-id` | tuning·sentinel | String | *(empty)* | no | Logical service name for registration. Empty = fall back to `freeway.app.name` (then `freeway-app`) |
| `freeway.app.name` | tuning·sentinel | String | `freeway-app` | no | Generic fallback key for the service name, read by cloud registration; note that the JVM `-D app.name` (without the `freeway.` prefix) is something else — it only determines the default log file name |
| `freeway.cloud.registry.service-host` | posture·auto | String | `auto` | no | Hostname to register. `auto` = if the bind address is a concrete address, use it (the server listens only there); with a `0.0.0.0`/`::` bind, prefer `POD_IP`, otherwise the first routable local address, and only when neither exists fall back to the bind address and warn. Multi-NIC hosts should name it explicitly |
| `freeway.cloud.registry.service-scheme` | posture·auto | String | `auto` | no | Registration scheme, which also decides whether the event mesh dials `ws` or `wss`. `auto` = follow whether the HTTP server has TLS enabled; an explicit value accepts only `http`/`https`, and any other value fails startup |
| `freeway.cloud.registry.shutdown-drain` | posture·auto | Duration | `auto` | no | **Shutdown drain window**: after deregistering and setting readiness to draining, keep serving for this period before stopping. `auto` = answered by the registry backend (`ServiceRegistry.drainWindow()`): the built-in in-process registry answers `0s` (no propagation delay within the same JVM), registry-center adapters carry their own propagation window (for example `5s`), so it need not be written per deployment; an explicit duration takes precedence, and a negative value fails startup |
| `freeway.cloud.registry.token` | decision | String | *(empty)* | no | **Shared key for the registry surface; one key used by both sides** (isomorphic to `event.token`): the node requires it and the client presents it (the `X-Registry-Token` header), so **both values must match**. Empty = no authentication (the node panel is open and clients send no header) — a development posture; set it once the node leaves the local machine |
| `freeway.cloud.registry.token-previous` | decision | String | *(empty)* | no | **Rotation window**: an old key accepted alongside `token`. Rotation steps: put the current value here, write the new value into `token`, roll the writers over, and finally clear this key — the node stays up throughout. Empty = no window; both empty = the surface is open |
| `freeway.cloud.registry.cache-ttl` | tuning | Duration | `5s` | no | **Reuse window for discovery results** (caching is the discovery implementation's freshness policy, not the caller's business): `0` = ask on every call. 5s is half a heartbeat (heartbeat 10s), well inside the 30s eviction window; when the backend fails, the oldest answer is served for the window (grace = max(10×ttl, 30s)), and only beyond that does it fail loudly |
| `freeway.cloud.registry.cache-ttl.<serviceId>` | tuning | Duration | *(inherits global)* | no | **Per-service override**: if configured it takes effect, otherwise the global value is inherited; `0` = no caching for that service. A dynamic key family admitted by prefix via `KnownKeys.admit` (the same approach as the per-file logging keys); an invalid value warns once and falls back to global |
| `freeway.cloud.registry.service-port` | tuning·sentinel | Integer | *(empty)* | no | Registration port. Empty = the HTTP server's actual listening port |
| `freeway.cloud.registry.service-instance-id` | tuning·sentinel | String | *(empty)* | no | Stable instance-level identifier. Empty = the derived key `service-id@host:port` |

**Registered node fleet (client side, `FleetRegistryModule`)** — placing the module selects this backend; the two keys are configured on the client process, and the shared secret is the `freeway.cloud.registry.token` above (the same key, the same value on both sides):

| Key | Class | Type | Default | Required | Meaning |
|----|------|------|--------|------|------|
| `freeway.cloud.registry.nodes` | posture·presence | String | *(empty)* | yes* | **Node fleet addresses**, comma-separated, each item `host:port` (interpreted as `http://`) or a full `http(s)://host:port`. Writes fan out to every node, reads take the union — the fleet is usable while any node is alive. Module placed but this key empty = startup fails and names this key |
| `freeway.cloud.registry.timeout-ms` | tuning | Long | `3000` | no | Connection + request timeout for a single node exchange (milliseconds) |

#### RPC / Remote invocation

| Key | Class | Type | Default | Required | Meaning |
|----|------|------|--------|------|------|
| `freeway.cloud.rpc.connect-timeout` | tuning | Long | `3000` | no | Connection timeout (milliseconds) |
| `freeway.cloud.rpc.request-timeout` | tuning | Long | `10000` | no | Request timeout (milliseconds) |
| `freeway.cloud.rpc.shutdown-grace` | tuning | Duration | `5s` | no | **Outbound call finishing window**: at shutdown, reject new calls first, then wait for in-flight calls to complete; only calls that still have no result when the timeout expires fail with `RpcHttpClient is closed`. An idle process closes immediately (no in-flight calls means no wait), so the default only costs time when there really are calls; keep it within the deployment's terminationGracePeriod |
| `freeway.cloud.rpc.trace.enabled` | posture | Boolean | `true` | no | Enable RPC call tracing |
| `freeway.cloud.rpc.resilience` | tuning·aggregate switch | String | `auto` | no | **Aggregate switch**: `auto` = the detailed keys below take effect individually; `off` = master off (no retries, NOOP circuit breaker, unlimited rate limiting), ignoring all detailed keys. Use it when the mesh takes over (the platform already does retries/circuit breaking, so the application layer must step aside) and to isolate variables during fault diagnosis. An invalid value fails startup |

#### Resilience — retry

| Key | Class | Type | Default | Required | Meaning |
|----|------|------|--------|------|------|
| `freeway.cloud.rpc.retry.max-attempts` | tuning | Integer | `3` | no | Maximum retry attempts (ignored when `resilience=off`) |
| `freeway.cloud.rpc.retry.backoff-base` | tuning | Long | `100` | no | Retry backoff base (milliseconds) |
| `freeway.cloud.rpc.retry.backoff-max` | tuning | Long | `5000` | no | Maximum backoff time (milliseconds) |
| `freeway.cloud.rpc.retry.budget` | tuning | Integer | `20` | no | Per-service retry tokens; success adds one, retry consumes one, `0` = unlimited |

Retries pass through two gates: the failure category (connection failure/timeout/mid-flight I/O/5xx are
retryable; 4xx and local rejections are not) and the **idempotency gate** — timeout/mid-flight I/O/5xx are
"unknown outcome" (the peer may have executed the request), so they are replayed only for idempotent operations.
A per-service **retry budget** caps the replay pressure across calls: successful calls replenish it, retries
consume it, and an exhausted budget fails fast with the original failure. It is separate from `max-attempts`
(one call's bound) and protects against a fleet-wide retry storm.
Idempotency is carried by the request: derived from the HTTP verb (`GET/HEAD/PUT/DELETE/OPTIONS/TRACE` are
idempotent, `POST/PATCH` are not), explicitly overridden by `RpcRequest.withIdempotent(true)`, and declared for
remote calls by the consumer interface's `@Idempotent` annotation (method-level/interface-level). A
connection-class failure means the request never arrived, so any operation can be retried.

#### Resilience — circuit breaker

| Key | Class | Type | Default | Required | Meaning |
|----|------|------|--------|------|------|
| `freeway.cloud.rpc.circuit-breaker.enabled` | posture | Boolean | `true` | no | Enable the circuit breaker — the default is the production posture; write `false` only when you explicitly want it off (ignored when `resilience=off`) |
| `freeway.cloud.rpc.circuit-breaker.failure-threshold` | tuning | Integer | `5` | no | Circuit-breaker trip threshold (failures within the sliding window) |
| `freeway.cloud.rpc.circuit-breaker.failure-window` | tuning | Long | `60` | no | Sliding window duration (seconds) |
| `freeway.cloud.rpc.circuit-breaker.open-window` | tuning | Long | `30` | no | How long the open state lasts (seconds) |

#### Resilience — rate limiting

| Key | Class | Type | Default | Required | Meaning |
|----|------|------|--------|------|------|
| `freeway.cloud.rpc.rate-limit.enabled` | posture | Boolean | `false` | no | Enable rate limiting. Opt-in (ignored when `resilience=off`) |
| `freeway.cloud.rpc.rate-limit.per-second` | tuning | Double | `100` | no | Maximum requests per second |

#### MeshEventBus — cross-node event mesh

Inbound interest is **not a configuration key**: it is declared by `MeshSubscription` contributions made
during composition — one declaration simultaneously produces the hello fetch prefixes, the inbound gate
(topics that were not declared are discarded on arrival without deserialization) and the delivery routes.

| Key | Class | Type | Default | Required | Meaning |
|----|------|------|--------|------|------|
| `freeway.cloud.event.peers` | posture·presence | String | *(empty)* | no | **Peer node list (presence primary key)** — non-empty enables the mesh; empty = a pure listener that needs an explicit `enabled=true`. Confirm network reachability before enabling |
| `freeway.cloud.event.discovery-feed-ms` | posture·presence | Long | *(unset)* | no | **Discovery feed interval (presence key; setting it enables it)**: every N milliseconds, query the bound `ServiceDiscovery` for instances of this service and replace the mesh's discovered set wholesale (this node is excluded). Nodes coming and going in the discovery backend configure the mesh automatically; a failed query keeps the previous set (a single failure must not be read as "everyone left"). Unset = off; a value < 1 fails startup and names this key. There must be a discovery to ask (`CloudModule` or an adapter), otherwise startup fails |
| `freeway.cloud.event.keepalive-ms` | posture·presence | Long | *(unset)* | no | **Mesh half-open connection probing**: setting it enables it; every N milliseconds send a WebSocket ping to each peer. Only one probe is in flight at a time; the pong budget is derived as `max(3 × interval, 1 s)` (not "the next tick"), and only when the budget is exhausted is the connection closed and handed to the existing reconnect/deregister path. This catches connections silently dropped by NAT/LB (TCP is still alive but the peer no longer reads); the 1 s floor ensures a single JVM pause/scheduling delay does not falsely kill a healthy link. Unset = off (leaving only send failures and TCP keepalive as signals); a non-positive value fails startup |
| `freeway.cloud.event.enabled` | posture | Boolean | *(unset)* | no | Explicit master switch; **if set, it wins**: `true` enables (including a discovery-fed mesh with no peers), `false` = master off (suppresses even configured peers). When unset, the presence rule applies (non-empty peers enables). Setting nothing = the module is installed but does nothing |
| `freeway.cloud.event.token` | decision | String | *(empty)* | no | Mesh handshake shared secret (empty = no peer authentication). **Required for multi-node production**: the same value on all nodes, injected via `FREEWAY_CLOUD_EVENT_TOKEN`; a mismatch disconnects with WS `1008`, and rotation requires a rolling restart |
| `freeway.cloud.event.connect-timeout-ms` | tuning | Long | `3000` | no | Outbound dial socket connection timeout (milliseconds) |
| `freeway.cloud.event.handshake-timeout-ms` | tuning | Long | `10000` | no | Handshake watchdog: timeout for waiting for hello/ack after the connection is established (milliseconds) |
| `freeway.cloud.event.backoff-base-ms` | tuning | Long | `1000` | no | Reconnect backoff base (milliseconds, exponential backoff) |
| `freeway.cloud.event.backoff-max-ms` | tuning | Long | `30000` | no | Reconnect backoff ceiling (milliseconds) |

#### RPC / TLS

| Key | Class | Type | Default | Required | Meaning |
|----|------|------|--------|------|------|
| `freeway.cloud.rpc.tls.key-store` | posture·presence | String | *(empty)* | no | **presence primary key** — RPC client keystore path; non-empty enables mTLS; empty = the plaintext development default |
| `freeway.cloud.rpc.tls.key-store-password` | required·conditional | String | *(empty)* | no | RPC client keystore password |
| `freeway.cloud.rpc.tls.trust-store` | decision | String | *(empty)* | no | RPC client truststore path |
| `freeway.cloud.rpc.tls.trust-store-password` | decision | String | *(empty)* | no | RPC client truststore password |
| `freeway.cloud.rpc.tls.reload-interval` | posture·presence | Duration | *(unset = no rotation)* | no | **Hot rotation of outbound TLS material**: when set, every interval allows the next handshake to pick up new keystore/truststore material (lazy, refreshed at handshake time, no background thread; a failed read keeps the old material). Unset = resolve once, rotation requires a restart — the same convention as the inbound `freeway.http.ssl.reload-interval`. Why it exists: the JDK `HttpClient` captures its `SSLContext` at construction time, so the correct way to change material is to have the context's KeyManager/TrustManager delegate to rotatable material rather than rebuilding the client |

#### Auth propagation

| Key | Class | Type | Default | Required | Meaning |
|----|------|------|--------|------|------|
| `freeway.cloud.auth.extract.enabled` | posture | Boolean | `false` | no | Enable inbound `x-principal` extraction (trust only inside the service network) |

### Example

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
        "token": "",
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

> Note: `secret.file` / `secret.keys` take effect only via `-D` system properties and do not
> participate in the file cascade, so they are not included in the JSON example above (see the
> secrets table).

---

## VI. Flow — Workflow Engine

The Flow module provides no externalized configuration keys. All configuration is done through the programmatic API:

- **GraphSpec** builder: `GraphSpec.create("id", spec -> { ... })`
- **NodeSpec** builder: `.putMetadata(key, value)`, `.when(...)`, `.task(...)`, `.linkTo(to, config)`
- **Node types**: START / END / ACTIVITY / EXCLUSIVE / INCLUSIVE / PARALLEL / LOOP
- **Graph version**: v2 DAG format (`GraphSpec.VERSION = 2`)
- **Execution constraints**: maximum recursion depth `1000`, maximum LOOP iterations `100_000`
- **PlantUML**: `PlantUmlOptions` controls the output format

---

## VII. IoC — Container

The IoC container provides no externalized configuration keys. All configuration is done through the programmatic API:

- **Bindings**: `binder.bind(X.class).to(Y.class)` / `.to(c -> ...)`
- **Scopes**: `bind().scope(SINGLETON | PROTOTYPE | BOUNDARY)`
- **`.primary()`**: engine/pool/dialect selection — the default implementation's binding has no `.primary()`, and an extension module binding a replacement carries `.primary()`, which the container resolves automatically
- **Injection**: `@Inject`, `@Symbol` (strict lookup or expression expansion)
- **Extensions**: the `Contribution<RuntimeHook>` and `Extension` mechanisms
- **Bean selection**: via `binding.primary()` rather than annotations

---

## Configuration type reference

| Type | Format | Example |
|------|------|------|
| String | Text | `"127.0.0.1"` |
| Integer | Number | `8080` |
| Long | Number | `10485760` |
| Double | Number | `100.0` |
| Boolean | `true` / `false` | `true` |
| Duration | ISO-8601 or suffix | `2s`, `30s`, `10m`, `30m`, `PT1H` |
| List(String) | Comma-separated (same shape as the HTTP header multi-value convention) | `a,b,c`; whitespace around entries is trimmed automatically and empty entries are dropped; unset / `""` / `" , ,"` all yield an empty list |

Supported suffixes: `ms` (milliseconds), `s` (seconds), `m` (minutes), `h` (hours)

**Two spellings for list keys**: properties/env/CLI use a comma-separated string; application.json may write a native array (flattening joins it automatically into the same comma encoding); the two are equivalent —

```json
{ "freeway": { "cloud": { "event": { "peers": ["10.0.0.11:8080", "10.0.0.12:8080"] } } } }
```

Equivalent to `freeway.cloud.event.peers=10.0.0.11:8080,10.0.0.12:8080`. List entries must not contain commas (the same restriction as HTTP header lists).

---

## Unknown-key detection

At startup, boot compares the `freeway.*` keys actually declared by configuration — the file layer, CLI, environment-variable mapping, plus the `-D` system-property layer the container collects on its own — one by one against the vocabularies contributed by each module, and prints a WARN for every key nobody reads. **Each unknown key is reported once at startup, and reporting never blocks**: the warning names the key and that is all; configuration still takes effect and startup does not fail. The check runs once with the startup hook and **is not re-reported on hot reload**.

### Where the vocabulary comes from

Modules contribute the vocabulary for their own namespaces via `binder.contribute(KnownKeys.class)`, with two constructors:

- `KnownKeys.of(ConfigKeys.class, prefixes...)` — reflectively harvests all key names under those prefixes from the key table (`*ConfigKeys`), so new keys enter the table automatically and no second list needs maintaining; the key table and the vocabulary must agree, and a key outside the prefixes appearing in the table fails immediately at bind time rather than being quietly dropped (dropping it would take that entire namespace out of detection).
- `KnownKeys.admit(prefix)` — pure prefix admission: declares only a prefix, no key names, for dynamic namespaces whose key names cannot be enumerated; it never offers suggestions under that prefix and is never reported by the namespace rule below (admission is the exemption marker).

Partially dynamic namespaces are handled as "fixed keys in the table, dynamic keys exempt by admission": the fixed keys of `freeway.log.*` are all in the vocabulary (a typo gets a suggestion), while data-constructed names such as `freeway.log.file.<name>.*` are admitted by boot via `admit(LogConfig.FILE_PREFIX)` — their existence alone is not a defect, and they are never reported by the namespace rule.

### When a key is reported

- **Near neighbours get suggestions**: when the edit distance between the unknown key and a key in the vocabulary is ≤ 2, the key is reported with a spelling suggestion; within each vocabulary, closer distances come first and ties sort alphabetically, and after concatenating and deduplicating across vocabularies there are at most 3.
- **Declared namespaces are reported directly**: with no near neighbour but the key falling under a namespace some module has declared (a non-root prefix such as `freeway.http`, `freeway.cloud`), it is reported as well — a declared namespace means detection has grounds, and a key missing from the vocabulary means no module reads it:

  ```text
  Unknown config key 'freeway.cloud.service-addr' — namespace 'freeway.cloud' is declared but no module reads this key
  ```

- **Undeclared means silent**: the key's namespace has no vocabulary contribution (common in subset assembly — the module is not loaded, so detection has nothing to judge by), or is covered only by the root `freeway.` (boot's key table sits at the root, and the root declares no module surface) — both stay silent; guessing would only create noise.
- **Dynamic key families are exempt**: under a prefix admitted by `admit` (such as `freeway.log.file.`), key names cannot be enumerated and their existence alone is not a defect — only near-neighbour suggestions are reported, never a namespace-rule report.
- **Report, never block**: the warning changes no behaviour; an unknown key is not rejected, and configuration takes effect as usual.
- **Retired prefixes get a separate notice**: `freeway.web.*` does not enter this detection — HttpModule announces it separately and precisely, naming each `old key → new key` rename, and warns once only when the old key is configured and the current key is absent.
- **Application keys do not participate**: keys outside the `freeway.` prefix (process-level keys such as `app.name`, `slf4j.provider`) are out of scope — the framework's vocabulary covers only `freeway.*`.

### Example

Writing `freeway.log.file` one letter short makes the startup log print this WARN (message verbatim):

```text
Unknown config key 'freeway.log.fil' — did you mean 'freeway.log.file' or 'freeway.log.files'?
```

With only one near neighbour, ` or ` is omitted (for example `Unknown config key 'freeway.profil' — did you mean 'freeway.profile'?`), and there are at most 3 suggestions.

---

## Environment variable mapping

Default prefix `FREEWAY_`, underscores map to dots:
- `freeway.http.server.port` → `FREEWAY_HTTP_SERVER_PORT`
- `freeway.db.url` → `FREEWAY_DB_URL`
- `freeway.log.level` → `FREEWAY_LOG_LEVEL`

There is exactly one mapping rule, with no exceptions: **replace `.` in the key name with `_` and keep every other character as is**. `-` is an ordinary character and is not transformed — `key-store` and `key.store` are two different keys, and the same variable must never feed both (that ambiguity is exactly where "folding" mappings come from, and this framework does not use them).

Therefore a key containing hyphens has **literal hyphens** in its environment variable name:

```
freeway.http.ssl.key-store-password  →  FREEWAY_HTTP_SSL_KEY-STORE-PASSWORD
freeway.db.pool.max-size             →  FREEWAY_DB_POOL_MAX-SIZE
```

The shell's `export` does not accept names containing `-`; that is a shell limitation, not an exception to the mapping: pass them with `-D`, `env 'NAME=value'`, systemd `Environment=` or a container `-e`. A misspelled variable name (for example `_` instead of `-`) gets no special handling — it maps to a different key name; if nobody declares that key, the mapped key name enters unknown-key detection: when it is within ≤2 of a key in the vocabulary, a WARN with a spelling suggestion is printed at startup, and farther away it stays silent (see "Unknown-key detection" above) — in neither case does the configuration take effect. The same applies to a misspelled full `freeway.*` key on the CLI; keys without the `freeway.` prefix are treated as application keys and do not enter that detection.

Exception (read from JVM system properties, does not participate in the cascade above): `freeway.cloud.secret.file`,
`freeway.cloud.secret.keys` — the two can only be set with `-D`, otherwise they are silently ineffective.

Custom prefix `freeway.env.prefix=APP_`: `APP_SERVER_PORT` → `server.port` (passed through)
