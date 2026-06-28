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
  http11/            — Http11Connection, HttpParser
  http20/            — Http2Connection, frame/HPACK/stream management
  ws/                — WebSocket frame read/write protocol
WebServer            — filter chain, routing, event publishing (Consumer<Object>)
HttpModule           — IoC bridge: registers FreewayHttpEngine, wires EventBus
```

### Standalone use (no IoC)

```java
var server = WebServer.builder()
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
| `http/engine/` | implementation | `FreewayHttpEngine` (public), shared I/O (BufferedIn/Out, ChunkedIn, FixedLengthIn), WS frame protocol |
| `http/engine/http11/` | package | Http11Connection, HttpParser |
| `http/engine/http20/` | package | Http2Connection, 29 HTTP/2 frame/HPACK/stream files |
| `http/engine/ws/` | package | WebSocketFrame, WebSocketSessionImpl, WsUtil, opcode/close enums |

## Performance

See `freeway-benchmark` module for reproducible benchmarks. Run:

```bash
mvn -pl freeway-benchmark exec:java \
  -Dexec.mainClass=...BenchmarkRunner \
  -Dbench.engine=freeway -Dbench.mode=keepalive
```

### Baseline (2026-06, keep-alive, 2 conns, 2000 reqs, independent JVM)

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

**WebSocket**: Fixed masking key handling (RFC 6455 §5.3), corrected fragmented
message reassembly, fixed close frame echo handshake, added UTF-8 validation,
replaced busy-wait with blocking I/O.

**General**: All I/O migrated from NIO selectors to virtual-thread blocking I/O,
removed Robaho's Logger in favor of SLF4J, unified error handling through
ExceptionMapper pipeline, integrated with Freeway's request/response lifecycle.
