# Freeway HTTP

Built-in HTTP engine for Freeway. Provides a virtual-thread-based,
synchronous I/O server with HTTP/1.1, HTTP/2 (h2c/h2), WebSocket, HTTPS,
and SSE support. No external dependencies beyond the JDK.

## Engine

`FreewayHttpEngine` is the default engine bound by `HttpModule`. When an
alternative engine is available on the classpath and bound with `.primary()`
(e.g. `UndertowEngine`), the container resolves it automatically — no
config keys needed.

| Feature | Status |
|---|---|
| HTTP/1.1 | Built-in |
| HTTP/2 (h2c/h2) | Built-in |
| WebSocket | Built-in |
| HTTPS | Built-in |
| SSE | Built-in |
| Static files | Built-in |
| Multipart | Built-in |
| CORS filter | Built-in |
| Health check | Built-in |

## Attribution

The HTTP/2 and WebSocket implementations were ported from
[robaho-httpserver](https://github.com/robaho/httpserver), a clean-room
Java HTTP server written by Robert Harder.

The ported components include:

- **HTTP/2** — frame serialization (`frame/`), HPACK header compression
  (`hpack/`), connection and stream management, settings negotiation,
  flow control, and error handling.
- **WebSocket** — frame encoding/decoding, opcode and close-code
  enumeration, upgrade handshake, ping/pong, and session lifecycle.

## Corrections and Enhancements

The following changes were made to the ported code:

### HTTP/2
- Replaced callback-based I/O with virtual-thread synchronous model
- Fixed HPACK dynamic table eviction to conform to RFC 7541 §4.3
- Corrected CONTINUATION frame handling for headers exceeding the max
  frame size
- Fixed stream state transitions for half-closed (remote) streams
- Added proper `RST_STREAM` and `GOAWAY` error propagation
- Fixed settings acknowledgment handshake during connection preface
- Corrected priority tree weight normalization
- Replaced custom concurrency with `java.util.concurrent` primitives

### WebSocket
- Fixed masking key handling for client-to-server frames per RFC 6455 §5.3
- Corrected fragmented message reassembly across frame boundaries
- Fixed close frame handshake — both endpoints now properly echo close
  frames before terminating
- Added message size limits to prevent memory exhaustion
- Fixed UTF-8 validation for text frames per RFC 6455 §8.1
- Replaced busy-wait loops with `CountDownLatch` / blocking I/O

### General
- All I/O migrated from NIO selectors to virtual-thread blocking calls
- Removed Robaho's `Logger` abstraction in favor of SLF4J
- Unified error handling through Freeway's `ExceptionMapper` pipeline
- Integrated with Freeway's request/response lifecycle and filter chain
