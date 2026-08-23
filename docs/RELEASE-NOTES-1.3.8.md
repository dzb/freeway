# Freeway 1.3.8 Release Notes

> Version: 1.3.8 ｜ Highlight: **freeway-http-engine has evolved into a full-featured HTTP engine**
> ~190 commits since 1.3.6, 67 of them in the HTTP module.

## Headline: freeway-http-engine → a comprehensive HTTP engine

The built-in engine is no longer just HTTP/1.1 connection handling — it is now a complete HTTP protocol stack:

- **Dual-protocol support** — HTTP/1.1 and HTTP/2 share one session architecture (`HttpSession` /
  `Http1xSession` / `Http2Session`), built on common connection, request and response abstractions.
- **Unified data model** — single `Headers` store (multi-valued response headers supported),
  streaming `RequestBody` access, semantic `HttpRequest`/`HttpResponse` interfaces, and an
  `ExchangeMeta` exchange facade.
- **Protocol hardening** — rejects control characters in headers and invalid HTTP/2
  `:path`/`:authority` (anti request-smuggling / proxy-confusion), answers malformed HTTP/1.1
  requests instead of dropping them, strict `Content-Length` parsing, and HTTP/2 compliance fixes.
- **Resource management** — TCP keepalive probes reclaim dead peers, read timeouts no longer kill
  active HTTP/2 streams, GOAWAY is announced on graceful shutdown, keep-alive state is isolated,
  and HTTP/2 connections close before stream handlers drain.
- **WebSocket & SSE** — WebSocket sends are fragmented above the frame cap, idle-timeout cleanup
  fixed, disabled CORS no longer gates upgrades; SSE heartbeats keep quiet streams alive through proxies.
- **Streaming & observability** — streaming request body access, RFC 7231 `Date` header, client IP
  injected into request contexts and access log, consolidated `HttpMetrics`, and an
  `HttpServerConfig.builder()` for configuration.
- **SPI cleanup** — `ExceptionMapper` → `ErrorHandler`, `RequestPipeline` restored as a public
  adapter SPI, deprecated bridges removed, and `RouteIndex` now reuses `PathPattern` template parsing.

## Other Modules

- **freeway-ioc** — tightened lifecycle: EventBus closes only after all lifecycle callbacks, the
  container seals atomically, injection/event failures fail loudly, `@Inject("id")` qualified
  injection, and final-field vs. getter-only injection errors are distinguished.
- **freeway-flow** — restored typed `Graph`/`Link` meta accessors and `FlowTrace.recordNodeId`,
  gateway dead-ends fail fast, `&&`/`||` short-circuit evaluation, and
  `FlowExchanger.getSteps()` → `steps()`.
- **freeway-db** — non-transactional DDL migration guards for MySQL/MariaDB, cross-thread
  transactions rejected, unknown JDBC URLs fail fast, and extended dialect capabilities
  (backslash escaping, RETURNING semantics).
- **freeway-boot** — stricter CLI parsing (bare `--`/`-D` rejected), empty `application.json`
  treated as no config, duplicate modules fail fast.
- **freeway-commons** — deterministic SLF4J provider selection (logback > log4j > simple),
  bounded JSON parsing, `Defer.propagating` context-propagating executor, typed `ConfigSpec`
  configuration, `@Marker` concurrency-contract annotations, and a metrics SPI.

## Compatibility Notes

- Behavior changes focus on stricter protocol validation and config parsing, each with explicit
  errors and migration guidance; deprecated SPIs are removed — migrate to `ErrorHandler`,
  `RequestPipeline`, `HttpRequest`, etc.
