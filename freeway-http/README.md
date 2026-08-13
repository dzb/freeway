# Freeway HTTP

Built-in HTTP engine for Freeway. Provides a virtual-thread-based,
synchronous I/O server with HTTP/1.1, HTTP/2 (h2c/h2), WebSocket, HTTPS,
and SSE support. No external dependencies beyond the JDK.

## Engine

`FreewayHttpEngine` is the default engine. It is constructed directly with
a `JsonCodec` and `Coercer` — no IoC container required for standalone use.

| Feature | Status |
|---|---|
| HTTP/1.1 | ✅ Built-in |
| HTTP/2 (h2c/h2) | ✅ Built-in |
| WebSocket | ✅ Built-in |
| HTTPS | ✅ Built-in |
| SSE | ✅ Built-in |
| Static files | ✅ Built-in |
| Multipart | ✅ Built-in |
| CORS filter | ✅ Built-in |
| Health check | ✅ Built-in |

## Architecture

Three-layer design:

```
engine/              — socket I/O, protocol parsing, connection lifecycle
  (HttpConnection, Http1xSession, Http1xParser live directly here)
  http2/             — Http2Connection, frame/HPACK/stream management
  ws/                — WebSocket frame read/write protocol
WebServer            — filter chain, routing, event publishing (Consumer<Object>)
HttpModule           — IoC bridge: registers FreewayHttpEngine, wires EventBus
```

### Standalone use (no IoC)

```java
var server = WebServerBuilder.builder()
    .config(new HttpServerConfig("0.0.0.0", 8080, 0, Duration.ofSeconds(2)))
    .route(Route.get("/ping", ctx -> ctx.send(200, "pong")))
    .build();
server.start();
```

### With IoC (FreewayApp)

```java
FreewayApp.run(args, new HttpModule(), binder -> {
    binder.contribute(Route.class).add(Route.get("/ping", ...));
});
```

## Package structure

| Package | Visibility | Contents |
|---|---|---|
| `http/` | public API | WebServer, HttpContext, HttpEngine, RouteIndex, filter interfaces |
| `http/engine/` | implementation | `FreewayHttpEngine` (public), `HttpConnection`, `Http1xSession`, `Http1xParser`, HTTP/1.x framing and shared I/O |
| `http/engine/http2/` | package | Http2Connection, 29 HTTP/2 frame/HPACK/stream files |
| `http/engine/ws/` | package | WebSocketFrame, WebSocketSessionImpl, WsUtil, opcode/close enums |

## Performance

The `freeway-benchmark` module lives outside this repository. Historical
baseline (2026-06, keep-alive, 2 conns, 2000 reqs, independent JVM):

| Engine | rps | p50 | vs robaho |
|---|---|---|---|
| FreewayHttpEngine | 8,342 | 177μs | +6.8% |
| robaho-native | 7,814 | 238μs | baseline |
| jdk-native | 6,888 | 269μs | -13.4% |

## Attribution

The HTTP/2 and WebSocket implementations were ported from
[robaho-httpserver](https://github.com/robaho/httpserver), a clean-room
Java HTTP server written by Robert Harder. See the file-level copyright
headers for individual ported components.

### Corrections and Enhancements

**HTTP/2**: Replaced callback-based I/O with VT blocking model, fixed HPACK
dynamic table eviction (RFC 7541 §4.3), corrected CONTINUATION/stream state
transitions, added proper RST_STREAM and GOAWAY propagation, fixed settings
ACK handshake, replaced custom concurrent primitives with `java.util.concurrent`.
Cleartext HTTP/2 supports both prior knowledge (`PRI * HTTP/2.0`) and the
RFC 7540 §3.2 `Upgrade: h2c` negotiation, including the original HTTP/1.1
request mapped to stream 1; requests that decline the upgrade (for example
with a body) fall back to ordinary HTTP/1.1 processing.

**WebSocket**: Fixed masking key handling (RFC 6455 §5.3), corrected fragmented
message reassembly, fixed close frame echo handshake, added UTF-8 validation,
replaced busy-wait with blocking I/O.

**General**: All I/O migrated from NIO selectors to virtual-thread blocking I/O,
removed Robaho's Logger in favor of SLF4J, unified error handling through
ExceptionMapper pipeline, integrated with Freeway's request/response lifecycle.
TLS is configurable through `HttpModule` (`freeway.http.ssl.*`): optional
truststore for client-certificate validation, `client-auth` for mutual TLS,
and protocol/cipher-suite restrictions.

## Operational controls

- `freeway.http.server.read-timeout` (default 30s, `0` disables): socket idle
  timeout covering request reads, TLS handshakes, HTTP/2 frames, and
  keep-alive waits.
- `freeway.http.server.write-timeout` (default 30s, `0` disables): a socket
  write blocked longer than this (peer stopped reading) closes the connection
  instead of pinning the thread forever.
- `freeway.http.server.max-connections` (default 0 = unlimited): excess
  connections are rejected at accept time.
- Accepted sockets run with `TCP_NODELAY` and `SO_KEEPALIVE`.
- `freeway.http.server.receive-buffer-size` / `send-buffer-size` tune
  `SO_RCVBUF` / `SO_SNDBUF` on accepted sockets (0 = OS default).
- `Metrics` (freeway-commons SPI) is wired through `HttpModule` /
  `WebServerBuilder.metrics(...)`: counters for connections, requests,
  4xx/5xx responses, WebSocket/HTTP/2 connections, plus active-connection
  and in-flight-request gauges, and a `freeway.http.requests.duration` timer
  recording per-request handler time in nanoseconds (`freeway.http.*`).
- TLS supports SNI certificate selection and hot reload through
  `freeway.http.ssl.sni-directory` (per-hostname keystores named
  `<host>.p12/.jks`, with `default.p12` as the fallback) and
  `freeway.http.ssl.reload-interval` (polls keystore mtime/size; a transiently
  missing file keeps the previous context until the new one is in place).
- HTTP/1.1 `Expect: 100-continue` is acknowledged before the handler reads
  the body; streaming responses use `HttpContext.output(InputStream, long)`
  and static files stream from disk instead of being buffered whole.
- HTTP/1.1 requests without a valid single `Host` header are rejected with
  400 (RFC 7230 §5.4); HTTP/1.0 requests are exempt.
- gzip response compression is on by default for compressible content types
  (client opt-in via `Accept-Encoding`, minimum body size 256 bytes) and can
  be tuned with `freeway.http.compression.enabled` / `compression.min-size`.
  Unknown-length streaming responses use HTTP/1.1 `Transfer-Encoding: chunked`.
- Static file mounts support single-range `Range` requests (206/Content-Range,
  including suffix ranges and If-Range); multi-range requests fall back to the
  full body.
- Large static files on plain HTTP use the OS sendfile path
  (`FileChannel.transferTo` → `sendfile`): bodies ≥ 64KB bypass the
  user-space copy loop, and range slices of any size reuse the same path.
  HTTPS, HTTP/2, classpath resources, compressed responses, and small bodies
  automatically fall back to buffered streaming. sendfile transfers are
  tracked by the write-timeout watchdog and counted under
  `freeway.http.sendfile.transfers`.
- A text access log can be enabled with `freeway.http.access-log.enabled`
  (IoC) or `WebServerBuilder.accessLog(PrintStream)` (standalone).
