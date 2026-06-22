# Changelog

All notable changes to Freeway 2 will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.2.0] — 2026-06-22

### Added

- **`JULConsoleFormatter`** — ANSI-colored single-line JUL console output with TTY auto-detection. Colors disabled automatically when output is piped. Override with `-Dfreeway.log.color=always|never`. Opt out entirely with `-Dfreeway.log.format=simple` or `FREEWAY_LOG_FORMAT=simple`.
- **`HttpParser.bodyStream()`** — returns an `InputStream` for reading the request body that includes any bytes already buffered past the header boundary, followed by the remaining raw socket input. Eliminates the need for manual `ChunkedInputStream`/`FixedLengthInputStream` wrapping in `HttpSession`.
- **Named virtual threads** — HTTP connection handler threads now named `http-<remote-address>` for easier debugging and monitoring of per-connection activity.
- **`MySqlDialect`** — built-in MySQL/MariaDB dialect with backtick quoting, `AUTO_INCREMENT`, `VARCHAR(36)` UUID, `DATETIME(6)` Instant, `LONGBLOB` binary.
- **`SqliteDialect`** — built-in SQLite dialect with double-quote quoting, `AUTOINCREMENT`, `TEXT` UUID/Instant, `BLOB` binary, `sqlite_master` introspection.
- **Dialect auto-detection** — `detectDialect()` maps JDBC URLs to built-in dialects. H2 maps to PostgreSQL (or MySQL if `MODE=MySQL`). Explicit unknown dialect throws `IllegalStateException`; auto-detected unknown falls back with warning.
- **`SymbolSource.resolve(name, defaultValue)`** — default value overload. Returns `defaultValue` when the key is not found; delegates to `expand()` with `${name:default}` syntax.
- **`ReflectUtils.rawClass(Type)`** — shared utility in `commons/bean` extracting `Class<?>` from `Type`. Eliminates 5 duplicated implementations across commons/ioc/db.
- **`commons.util`** — consolidated utility package: `IoUtils` (bounded/readBytes streams), `Strings` (blankToNull, camelToSnake), `Maps` (nested flatten), `Digests` (sha256Hex/sha256Base64). Replaces `commons.io.InputStreams`.
- **Coercer API** — `canCoerce` → `supports`, `conversions` → `supported`, `CoerceRule.converter` → `mapping`. `coerceInternal` if-else chain replaced with O(1) `Map<Class, BuiltinCoercer>` dispatch; class reduced 531→370 lines.
- **Config keys** — `web.*` → `freeway.web.*`, `shutdown-grace-seconds` → `shutdown-grace` with `Duration` type. Profile-specific config samples for dev/prod in properties + JSON.
- **`List<T>` contribution injection** — contributions can now be injected directly as `List<T>` instead of requiring `Extension<T>` + manual `.all()`. Constructor params auto-resolve; fields need `@Inject`. (`resolveContributed`)
- **`RowMapperResolver(Coercer, List<RowMapping>)`** — IoC-friendly constructor for contributed row mappers.
- **`DatabaseHubImpl(List<DatabaseNamed>)`** — IoC-friendly constructor for contributed named databases.
- **`HealthCheck`** — `@FunctionalInterface` for pluggable health endpoint responses. Default returns `{"status":"ok"}`; bind a custom implementation for DB/external service checks.
- **`HealthFilter`** — `HttpFilter` that intercepts the health endpoint (`freeway.web.health.enabled`, `freeway.web.health.path`) before routing. Injected into `WebServer` alongside `CorsFilter`.
- **CRLF validation in `headerSet`** — all `HttpContext` implementations now reject `\r`/`\n` in header values, preventing HTTP response header injection.

### Fixed

- `toUpperCase()`/`toLowerCase()` without `Locale.ROOT` across 12 files — Turkish locale would corrupt SQL DDL, config keys, column labels, and migration lock detection.
- `SymbolSource.resolve(name, null)` no longer expands to string `"null"` — fixes health-check-query default.
- `SqliteDialect.addColumn()` no longer doubles `ADD COLUMN`.
- `IndexDef.toSql()` conditionally omits `IF NOT EXISTS` for MySQL (via `Dialect.supportsIndexIfNotExists()`).
- `SchemaEntity.entityTypes()` returns cloned array — prevents external mutation.
- `SchemaEntity` constructor clones input array — prevents caller-side mutation.
- `RequestContext.attribute()` now validates null key (was inconsistent with `setAttribute()`).
- `StaticResourceMount` `URLDecoder` `+` → space bug fixed by pre-replacing `+` with `%2B`.

### Changed

- **`Coercer.coerce()`** — throws `IllegalArgumentException` instead of `IllegalStateException` for coercion failures.
- **`CorsFilter`** — only intercepts genuine CORS preflight (`Access-Control-Request-Method` header present). Non-preflight `OPTIONS` requests pass through to route handlers.
- **`HttpServerConfig`** — invalid port/backlog/shutdownGrace now throw `IllegalArgumentException`; `shutdownGrace` is now `Duration` (config key `freeway.web.server.shutdown-grace`, e.g. `2s`), consistent with DB pool duration keys.
- **`HealthFilter.normalize()`** — delegates to `PathPattern.normalizePath()`, stripping trailing slashes consistently.
- **`PathJoiner.normalize()`** — delegates to `PathPattern.normalizePath()` with root-path transformation.
- **`PathPattern.validateRegistrationPath()`** — rejects empty path segments (`/a//b`), unbalanced braces (`/{id`), and empty parameter names (`{}`, `/:regex`).
- **`RouteIndex`** — wildcard params (`{path:.*}`) now reject literal children and vice versa, preventing unreachable routes. Param conflicts now compare regex by pattern string (value equality).
- **`WebSocketIndex.match()`** — iterates in reverse; individuals (added last) override group routes (added first).
- **`WebSocketRoute`** — always rebuilds `PathPattern` from path in canonical constructor, guaranteeing path/pattern consistency.
- **`MigrationRunner.isDuplicateKey()`** — SQL state code checking (`state.startsWith("23")`) added as fallback to keyword matching.
- **`Schema.ensure()`** — `existingTables` refreshed after `CREATE TABLE` to prevent duplicate DDL for multi-entity same-table batches.
- **`Schema.ensure()`** — copies `existingTables` to `HashSet` for safe mutation by custom dialect implementations.
- **`SqlTextScanner`** — renamed to `SqlTextParser` and moved from `db/internal` to `db/util`.
- **`BatchQueryImpl`** — rejects mixed positional/named parameters at construction time. Defensive `List.copyOf()` for `rows()`/`named()` inputs.
- **`PoolDefault.release()`** — `Objects.requireNonNull(conn)` instead of silently returning on null.
- **`Extension.order()`** — throws `IllegalArgumentException` on unknown `before`/`after` ids (was silent skip).
- **`JdkHttpContext`** — `queryParams()` now returns deep-frozen map with immutable inner lists. `headers()` returns `List.copyOf()` (was mutable).
- **`StubHttpContext`** — request/response headers separated; request headers support multi-value (`List<String>`). `requestHeader()` fluent setter, `responseHeader()` query method. `headerSet()` validates CR/LF. `queryParam()` allows null→empty for bare params.
- **`RequestContext.create(String)`** — normalizes blank input to random UUID. `RequestContextDefault` constructor mirrors this behavior.
- **Environment variable mapping** — `FREEWAY_DB_URL` now maps to `freeway.db.url` (prefix stripped, `_` → `.`, `freeway.` prepended).
- **`@SuppressWarnings("SameParameterValue")` removed** — `coerceNumber` parameter narrowed to `Number`.
- **`BootConfigLoader`** — four one-use String constants inlined.
- **CI** — `mvn test` added before deploy in both snapshot and release workflows.
- **Extension mechanism simplified** — removed `Extension.Key` record (was `Class<?> entryType` + `String name`, the latter dead). `extensions` map changed to `Map<Class<?>, Extension<?>>`. FQN-based binding registration removed; extensions live exclusively in their own `ConcurrentHashMap`.
- **`Binder` API cleaned** — removed unused `contribute(Class, String name)` overload. Removed never-implemented `contributeMapped`.
- **`Container.extension()`** — default method changed to abstract; removed misleading `get(Extension.class, entryType.getName())` fallback.
- **HTTP internals** — `RouteIndex`, `WebSocketIndex`, and `WebServer` constructors now take `List<T>` instead of `Extension<T>`. Seven parameters dropped `.all()` calls.
- **`InjectResolver` restructured** — `Extension<Foo>` and `List<Foo>` resolution moved into dedicated `resolveContributed()` method, fixing a hidden bug where `@Inject Extension<Foo>` on fields would construct a broken empty Extension instance.
- **HTTP package restructuring** — filter, route, body, event, sse, staticfile, and websocket classes extracted into sub-packages. `JdkHttpContext`/`JdkHttpEngine`/`RequestContextDefault` moved from `internal` back to root. `PathJoiner` moved to `route`. `RequestBodyTooLargeException` renamed to `BodyTooLargeException`. Test packages mirrored to match source layout.
- **`PooledConnection` interface** — extracted from the old concrete class (now `PooledConnectionDefault`). Public `Pool` API now returns the interface, eliminating the cross-module `internal` boundary violation in the HikariCP adapter.
- **`HikariPoolModule`** — now binds `Pool.class` instead of `HikariPool.class`, aligning with `DbModule.resolvePool()`.
- **`Schema.ensure()` / `drop()`** — no-dialect convenience overloads removed; caller must supply explicit dialect. `SchemaGenerator` no-arg constructor removed.
- **`SqlTypeMapping.BASIC_TYPES`** — shared type set extracted; `RowMapperResolver.isBasicType()` delegates to `SqlTypeMapping.isBasicType()`, eliminating duplicated type lists.
- **`Coercions`** — `registerJdbcDefaults()` removed; callers use `jdbcDefaults()` directly for a single entry point.
- **`Names`** — moved from `db` to `db/util`.
- **Schema package Javadoc** — all Chinese comments converted to English across 13 files.
- **`HikariPool`** — now tracks `borrowCount` via internal counter (was hardcoded 0 in stats). Added 7 integration tests covering concurrency, exhaustion, close semantics, and health check query forwarding.
- **`WebSocketRoute`** — `PathPattern` now cached at construction time instead of re-parsed on every match.
- **`StaticResourceMount.StaticAsset`** — ETag computed once at construction (was SHA-256 per request).
- **`WebServer` filter chain** — pre-built in constructor instead of reconstructed per request.
- **Robaho `WebSocketSession`** — request headers snapshotted at upgrade time, matching Undertow/Jetty behavior.
- **`UndertowWebEngine` exception handling** — removed double `RuntimeException` wrapping of handler errors.
- **`HttpSession.createBodyStream()`** — removed private helper; body stream creation moved to `HttpParser.bodyStream()`, which properly handles bytes already buffered past the header boundary.
- **Engine selection** — switched from config-key-based (`freeway.web.engine`) to `.primary()`-based IoC resolution. `HttpModule` binds `FreewayHttpEngine` without `.primary()`; extension modules (e.g. `UndertowModule`) bind with `.primary()`. No config key needed — just add or remove the extension module.
- **DEVELOPER-GUIDE.md** — updated engine switching section with `.primary()` mechanism explanation, code examples, and corrected module tree (removed robaho/jetty references).
- **SKILL.md** — updated module tree and added HTTP engine switching section in Chinese, matching DEVELOPER-GUIDE.md.
- **Enhanced test coverage** — HTTP module 30→62 tests (`FilterChain`, `ExceptionMapper`, `StaticResourceConditional`, `ClasspathResourceSource`, `HealthFilter`, engine fallback, static fallthrough). DB: `RowTest`, `PooledConnectionDefaultTest`.

### Removed

- **`JdkHttpContext` / `JdkHttpEngine`** — built-in JDK `com.sun.net.httpserver` engine removed. The only built-in engine is now `FreewayHttpEngine`. Users needing an alternative engine add `freeway-http-undertow`.
- **HTTP/2 frame types** — flat `engine/` subpackage classes restructured into `engine/http20/frame/`, `engine/http20/hpack/`, and `engine/http20/util/`. Deleted: `BufferedBuilder` (replaced by `StringBuilder`), `ChunkedOutputStream` (replaced by inner class), `FixedLengthOutputStream` (unused).
- **Strict mode (`freeway.strict`)** — removed entirely. Duplicate modules now logged (not thrown). Unbound concrete types always auto-instantiate. Engine fallback always warns + falls back. Eliminates `System.setProperty` side channel between `AppBuilder` and `ContainerImpl`/`WebServer`.
- **`NamedParamParser`** — thin 27-line delegation wrapper around `SqlTextParser`; `Result` record moved into `SqlTextParser`.
- **`MigrationRunner` dead scanning methods** — `skipLineComment`, `skipBlockComment`, `skipDollarQuote`, `appendQuoted`, `addStatement` (duplicate of `SqlTextParser.addStatement`).
- **`IsolationLevel` unused `sqlLevel` parameter** — JDBC constants already match SQL standard values.
- **`DbModule` defensive wrappers** — `resolveStr`, `parseInt`, `parseBool(SymbolSource,...)`, `parseDuration(SymbolSource,...)`, `isUnknownSymbol` replaced by `SymbolSource.resolve(name, defaultValue)`.
- **`JsonUtils.deepCopy(Object)`** — package-private method with zero callers.
- **`RowMapperResolver` null guards** — `customMap()` and `addAll()` dead null checks removed.
- **Extension adapter modules** — `freeway-http-robaho`, `freeway-http-undertow`, `freeway-http-jetty`, `freeway-mq-kafka`, and `freeway-db-hikari` moved to the [freeway-ext](https://github.com/dzb/freeway-ext) repository. Core modules (`commons`, `ioc`, `boot`, `http`, `db`) remain in this repository, keeping their zero-external-dependency guarantee.
- **`Extension.Key`** — the `(Class<?> entryType, String name)` record, superseded by bare `Class<?>` as map key.
- **`Binder.contribute(Class, String name)`** — dead API surface, no callers.
- **`DbModule.buildResolver()` / `buildHub()`** — static methods replaced with inline provider lambdas.
- **FQN-as-extension-id** — `EventBus` and two `DbModule` paths previously used `container.get(Extension.class, Xxx.class.getName())`; all now use `container.extension(Xxx.class)`.

## [1.1.1] — 2026-06-13

### Added

- **ScopedCache** — scoped value cache primitive built on top of JDK 25 `ScopedValue`. Provides a key-value cache that lives within a scope boundary and is automatically discarded on scope exit. Prunes the IoC scope layer by replacing heavier scope machinery with a lightweight cache primitive. (`78e448f`)
- **Module2** — `@FunctionalInterface` for module definitions. Adds `binder.install()` to compose modules declaratively. Enables multiple `FreewayApp` instances per JVM. (`2eadd5f`)
- Comprehensive **DEVELOPER-GUIDE.md** — dual-purpose documentation for humans and AI assistants, with a dedicated Module section. (`fd0f67c`, `20114cb`)

### Changed

- **StaticResourceMount** — added fallthrough behavior when no static file matches, allowing the request to continue to the next handler. (`097f218`)
- **Query.execute()** — new terminal operation for DML statements (INSERT/UPDATE/DELETE) that returns an `ExecuteResult`. (`097f218`)
- **Named parameter auto-bind** — query named parameters (`:name`) now auto-bind to matching record/bean property names. (`097f218`)
- **Generics audit** — eliminated all raw types and unchecked casts across the codebase. (`f1ed490`)

### Fixed

- Maven publishing metadata added to `freeway-db-hikari` and `freeway-mq-kafka` modules. (`878ad3e`)

## [1.1.0] — 2026-06-10

### Added

- **Defer** — scope-bound deferred execution mechanism (`com.jujin.freeway.commons.defer`). Actions buffered inside a scope drain on commit, discard on rollback. Powers transaction-aware `EventBus.publish()`, per-HTTP-request scopes, and per-Kafka-record scopes with zero user wiring. (`5b1aba8`)
- **EventBus** — in-process publish-subscribe event bus with string topics, `DeadEvent` diagnostics, O(1) subscriber indexing, and `publishAsync`. (`50605d5`, `58728ce`, `694425f`, `e55d14a`)
- **freeway-mq-kafka** — distributed EventBus extension via Kafka, enabling cross-process pub/sub with the same EventBus API. (`cd8e2ea`, `fc38a63`)
- **freeway-db-hikari** — HikariCP connection pool adapter. (`288c7ed`)
- **Connection pool abstraction** — `Pool` interface + `PoolDefault` built-in implementation, selectable via `freeway.db.pool`. (`afb5aa9`, `9b7187b`)
- **JsonCodec** moved into `freeway-commons`, making JSON serialization available without IoC dependency. (`fc38a63`)
- **Lifecycle events** — `AppStartedEvent` / `AppStoppingEvent` published on the EventBus. (`9efa7e9`)
- **HTTP events** — `HttpRequestEvent`, `HttpErrorEvent`, `AssetServedEvent`, WebSocket open/close events published on the EventBus. (`f8c5cff`)
- **Schema auto-migration** — `@Table`, `@Column`, `@Id`, `@Index` annotations + `AutoMigrate` for automatic DDL generation. (`f6a9ee9`)
- **Orm** — basic CRUD operations with audit support. (`5168479`)
- **freeway-db standalone** — `DatabaseBuilder` + `PoolConfig` allow DB usage without the IoC container. (`0c2211a`, `66406ad`)
- **ExecuteResult key** — `ExecuteResult.id` changed from `long` to `Object key`, supporting non-numeric auto-generated keys. (`732a4bf`)

### Changed

- **ExtensionPoint<V>** — new extension point system with `ScopedValue`-based thread scoping. (`d2f69fb`)
- **Database API** — `Database.query()` and `Database.execute()` split query vs write entry points. (`5e3eac5`)
- **Coercion system** — unified type conversion with `Coercer`/`CoerceRule` refactored; JDBC date coercion rules added. (`f36acdf`)
- **Logging bootstrap** — SLF4J `ServiceLoader` standardization; JUL-backed fallback provider only activates when no external SLF4J provider is detected. (`f55c85d`, `e140304`)
- **freeway-db** — decoupled from IoC container, making it usable as a standalone library. (`0c2211a`)
- Removed `afterCommit`/`TransactionContext` in favor of the Defer mechanism. (`bd71f4d`)

### Fixed

- Config cascade priority corrected: env vars now properly override file-based config. (`f993ddd`)
- `ConcurrentHashMap.computeIfAbsent` JDK 25 false recursion during engine readiness polling. (`0acd60d`)
- 304 Not Modified response not sent in `StaticResourceMount`. (`419415c`)

## [1.0.x] — 2026-05

### 1.0.8

- **ExecuteResult** — `long id` → `Object key` for non-numeric auto-generated keys.
- **EventBus** — initial in-process event bus implementation.
- **Orm** — basic CRUD with audit support.
- **Schema** — `@Table`/`@Column`/`@Id`/`@Index` + `AutoMigrate`.

### 1.0.7

- Logging completion + container close clears extensions.
- SLF4J `ServiceLoader` standardization.
- Unified naming, logging, and language feature style across codebase.
- `Database.query()` + `execute()` API split.

### 1.0.5

- ExtensionPoint<V> system + `ScopedValue` thread scoping.
- Coercion system refactored with JDBC date `CoerceRule`.
- Register/Module simplification.

### 1.0.3

- Refined naming conventions.
- Removed obsolete design and audit notes.

### 1.0.2

- Logging auto-configuration — auto-defers to external Logger when present.
- Dynamic SQL design document added.

## [Initial Release] — 2026-05

- **Freeway 2** initial release — a modern, lightweight Java application framework built on JDK 25+.
- **freeway-commons** — shared utilities: JSON, coercion, logging bootstrap.
- **freeway-ioc** — IoC container with singleton/prototype/thread scopes, constructor and field injection, `@Symbol`/`@Value` config injection, extension/contribution mechanism.
- **freeway-boot** — application launcher with config cascade (CLI → env → profile files → default files), profile activation, and runtime lifecycle hooks.
- **freeway-http** — HTTP/WebSocket layer with trie-based routing, path variables, regex constraints, static resources, multipart, SSE, pluggable engines.
- **freeway-db** — JDBC data access with ORM, connection pooling, transactions, and query builder with named parameters and collection expansion.
- Extension adapters (robaho, undertow, jetty, hikari, kafka) available in [freeway-ext](https://github.com/dzb/freeway-ext).

[1.1.1]: https://github.com/dzb/freeway/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/dzb/freeway/compare/v1.0.0...v1.1.0
