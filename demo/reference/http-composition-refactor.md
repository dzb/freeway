# HTTP / WebSocket Compositional Refactoring Plan

> **Status: implemented and revised through audit (2026-11-12).** Section 14 records the settled
> decisions, Section 15 records the first acceptance round, and Section 16 records the post-audit
> commit/framing/contract revisions; this document is also the baseline for this refactoring.
>
> Target repositories: `freeway` (core) and `freeway-ext`. Baseline: core
> `378b2d13`, working tree clean.
>
> Related conventions: "Extending versus composing" in `AGENTS.md`, the HTTP seam in
> `docs/architecture.md`, and "Core first, adapter second" in `freeway-ext/AGENTS.md`.

## 1. Goals

Eliminate implementation inheritance between framework types, leaving Freeway's HTTP / WebSocket extension points with nothing but
**interface contracts**:

- `HttpExchange`, `HttpRequest`, `HttpResponse`, `ExchangeMeta`,
  `RequestView`, and `WebSocketSession` remain the interfaces applications see; `extends` between
  interfaces is kept (contract composition, not implementation inheritance).
- Add two SPIs, `HttpTransport` / `WebSocketTransport`; engine adapters only
  `implements` them.
- Gather shared policy into the final `HttpExchangeSupport` /
  `WebSocketSessionSupport`, which engines **compose** instead of inheriting from.
- Delete `AbstractHttpContext`, `AbstractWebSocketSession`, and every class that
  `extends` them.
- Connect all Undertow and Jetty HTTP/WS adapters in `freeway-ext` to the new SPI, and
  migrate the testkit contracts and benchmarks along with them.

This is not a refactoring "to save a few lines of code". The payoff is structural:

1. The `extends` exception "implementation shared with third-party adapters" in
   `AGENTS.md` no longer needs to exist;
2. "Reset per request" goes from a convention that must be obeyed to the object lifecycle itself;
3. The three engines' status code/header validation, `Date`, `Content-Length`, gzip decision,
   and body limit go from "three copies that happen to agree" to "structurally one copy".

## 2. Boundaries: Which `extends` Are Kept

| Category | Handling | Examples |
|---|---|---|
| `class extends` between framework types | **Delete all** | `AbstractHttpContext`, `AbstractWebSocketSession` and their subclasses |
| `interface extends` between contracts | **Keep** | `HttpExchange extends ExchangeMeta, HttpRequest, HttpResponse` |
| JDK / language mandated | **Keep** | `RuntimeException`, `InputStream`, `OutputStream`, `X509ExtendedKeyManager`, `AutoCloseable` |
| Protocol hierarchies closed within a package | **sealed interface + composition** | `engine.http2.Http2Frame` and the frame types; not visible, not cross-module, no third-party implementors |
| Callback / listener interfaces | **Keep** | `WebSocketListener`, `Session.Listener`, etc. |

Acceptance commands (run in the core and ext root directories respectively):

```bash
# 1. No framework type is subclassed anymore
rg "extends (AbstractHttpContext|AbstractWebSocketSession)" \
   freeway-core freeway-ext

# 2. The two base classes and their names are gone entirely
rg "AbstractHttpContext|AbstractWebSocketSession" \
   freeway-core freeway-ext

# 3. Adapters implement only the SPI
rg "class .* (implements|extends)" \
   freeway-ext/freeway-http-undertow/src/main/java \
   freeway-ext/freeway-http-jetty/src/main/java
```

Items 1 and 2 must be empty. Item 3 allows JDK/third-party base classes (for example Jetty's
`Session.Listener` and Undertow's `AbstractReceiveListener`), but **no core framework type may
appear again**.

## 3. Current-State Audit

### 3.1 Inheritance points in core

In `freeway-http/src/main/java`, framework implementation inheritance exists in only two places:

| Type | Lines | Responsibility |
|---|---:|---|
| `http/AbstractHttpContext.java` | 357 | Metadata, path vars, body limiting, status code/header validation, JSON/type conversion, `Date`, Vary, send shorthands, SSE headers |
| `http/websocket/AbstractWebSocketSession.java` | 175 | Metadata snapshot of the upgrade request + frame-operation shell |

All other `extends` are JDK types (`InputStream`/`OutputStream`,
`RuntimeException`) or interface contract composition; the HTTP/2 frame family has already been changed to
the `engine.http2.Http2Frame` sealed interface + composition.

### 3.2 Inheritance points in ext

| Type | Inheritance | Notes |
|---|---|---|
| `UndertowHttpExchange` | `AbstractHttpContext` | Pooled in a `ThreadLocal`, reset by `reset()` |
| `JettyHttpExchange` | `AbstractHttpContext` | Same as above |
| `UndertowWebSocketSession` | `AbstractWebSocketSession` | Frame operations delegate to Undertow |
| `JettyWebSocketSession` | `AbstractWebSocketSession` | Frame operations delegate to Jetty |

The benchmarks do not inherit framework types, but three classes in `freeway-benchmark` directly
`new HttpContextImpl(...)` and call the package-private `reset(...)`; this is the only penetration
point of a core internal type into ext, and it must be migrated together with the refactoring.

### 3.3 Concrete coupling points

- The method parameters of `HttpResponseWriter` / `Http1xResponseWriter` / `Http2ResponseWriter`
  are of the concrete type `HttpContextImpl`, and they read status, headers, method, and
  keep-alive from it.
- `HttpSession.echoRequestId(HttpContextImpl)` takes the concrete type as a parameter.
- `JettyHttpEngine` calls the package-private `ctx.responded()`.
- `Http1xSession` reuses one `HttpContextImpl` per connection and calls
  `reset(...)` per request; `reset` is responsible for `resetExchangeMeta()`,
  `resetMaxBodySize()`, `responseHeaders.clear()`, and
  `pathVariables.clear()`.
- `UndertowHttpEngine` / `JettyHttpEngine` each pool `HttpExchange` via `ThreadLocal`,
  and `dispatch` calls `reset` first and then sets compression.
- `UndertowHttpExchange.body()` translates Undertow's `RequestTooBigException`
  into `BodyTooLargeException`; the translation point is currently mixed in with the HTTP logic.
- `WebSocketReadLoop`'s parameter is the concrete `WebSocketSessionImpl`, and the read loop depends on
  `markOpen/markClosed/writeFrame/closeCode/closeReason`.
- The 123-byte truncation in `AbstractWebSocketSession.closeReason(String)` is used only by
  adapters; the built-in engine's `WebSocketSessionImpl.close` throws
  directly for an over-long reason. This is an existing engine divergence.

## 4. Target Structure

### 4.1 HTTP

```
                 Application / framework components (filter, route, staticfiles, error)
                              │ depends only on
                              ▼
                        HttpExchange  (interface)
                              ▲ implements
                              │
                  HttpExchangeSupport  (final, the only shared implementation)
                  ├─ ExchangeMetaImpl          metadata
                  ├─ pathVariables             routing-variable snapshot
                  ├─ bodyLimit + body cache    request body limiting/caching
                  ├─ status + responseHeaders  response status
                  ├─ Date / Content-Length / Vary / gzip decision
                  └─ JSON / coercion / send shorthands
                              │ calls only mechanisms
                              ▼
                     HttpTransport  (public SPI)
                    ▲          ▲                ▲
                    │          │                │
        HttpTransportImpl   UndertowHttpTransport  JettyHttpTransport
        (built-in engine)   (freeway-ext)          (freeway-ext)
```

`HttpExchangeSupport` is a final **policy object**: it offers no override points and is not
inherited by any type. An engine's `HttpTransport` implementation is responsible only for "reading the request" and
"writing `ResponseHead` + body onto the wire".

### 4.2 WebSocket

```
WebSocketSession (interface)
      ▲ implements
WebSocketSessionSupport (final)
      │ metadata + close reason truncation + frame delegation
      ▼
WebSocketTransport (public SPI)
      ▲
      ├─ WebSocketConnection      (built-in engine: frame/connection state)
      ├─ UndertowWebSocketTransport
      └─ JettyWebSocketTransport
```

### 4.3 Lifecycle

- `HttpExchangeSupport`: **created anew for each HTTP request**, discarded when the request ends.
- `WebSocketSessionSupport`: **created anew for each upgraded connection**.
- `HttpTransport`: **created anew per request**; the SPI exposes no `reset`:
  - Built-in HTTP/1: one per request (the connection-level socket / parser stays in the session);
  - Built-in HTTP/2: one per stream;
  - Undertow / Jetty: one per request.
- No more `resetExchangeMeta()`, `resetMaxBodySize()`,
  `HttpContextImpl.reset(...)`, or adapter `ThreadLocal` context pools.
- `ExchangeMetaImpl.reset()` loses its only caller in core and is deleted along with it
  (`ExchangeMetaImpl` is an engine-internal `XImpl`, with no compatibility promise).
- The decision rests on the JMH evidence in Section 7.3: per-request transport + support
  costs about 64 ns/op more than pooling, far below the fixed cost of connection, parsing, and handler;
  what it buys is the complete disappearance of the reset obligation and the state-crosstalk failure mode.

## 5. Responsibility Matrix

| Responsibility | Owner | Notes |
|---|---|---|
| `correlationId` / `startTime` / principal / attributes | Support | Composes `ExchangeMetaImpl` |
| `pathVariables`, whole-replacement semantics of `setPathVars` | Support | New map per request, no leakage |
| `maxBodySize` + validation + `bodyLimit()` | Support | Per-request initial value is the constructor argument, no reset |
| `LimitedInputStream` wrapping, `body()` cache, `bodyText`, `bodyAsJson` | Support | All engines run the same limiter |
| Status code validation (100..599) | Support | Built-in engine already had it; adapters brought to parity |
| header name/value validation | Support calls `internal.HttpHeaders` | RFC 7230 token + ISO-8859-1 |
| `committed` guard, ignoring repeated output | Support | Unified semantics |
| `Date` fill-in (not overridden if the application already set it) | Support | Same behavior across all three engines |
| `Content-Length` computation/removal | Support | Framework authority; removed for 204/205/304 |
| gzip **decision** (status, min-size, Accept-Encoding, Content-Type) | Support | `ResponseFraming` remains the single rule source |
| `Vary: Accept-Encoding` merging | Support | Added only when compression actually happens |
| `output(String/Json)`, `send/sendJson`, `addVary`, SSE headers | Support | Logic from the former base class |
| Request reads: method/path/query/headers/body stream/TLS/remote | Transport | Engine-native |
| Response landing: status line/headers/body/file/SSE | Transport | Engine-native |
| HTTP/1.1 framing (CL / chunked / Connection) | Transport (writer) | Derived from `ResponseHead` |
| HTTP/2 header filtering (connection, etc.), DATA/END_STREAM/RST | Transport (writer) | Protocol details |
| gzip **execution** (compressing bytes/streams) | Transport | Decision in Support, execution in Transport |
| Native body limit translation (Undertow `RequestTooBigException`) | Transport | Unified into `BodyTooLargeException` before entering the framework |

## 6. API Design

### 6.1 `HttpTransport`

Location: `com.jujin.freeway.http.HttpTransport` (public SPI).

```java
public interface HttpTransport extends RequestHead {

    // -- request mechanisms --
    // method/path/queryParams/headers are inherited from RequestHead.

    /** Raw request body after transport-layer framing; called at most once per request. */
    InputStream framedBodyStream() throws IOException;

    boolean isSecure();
    SSLSession sslSession();          // may be null
    String remoteAddress();

    // -- response mechanisms --
    /** The single response execution entry point; head is already the final wire head, body is executed once. */
    void writeResponse(ResponsePlan plan) throws IOException;

    /** Write SSE headers and return the emitter; head carries no Content-Length. */
    SseEmitter openSse(ResponseHead head) throws IOException;
}
```

Contract highlights:

- `writeResponse(ResponsePlan)` is the single response execution entry point; body is a sealed
  `Bytes`/`Stream`/`File`/`Channel`/`Empty`, so the transport no longer has four sets of body methods.
- Encoding exists in exactly one place: the head's `Content-Encoding`; gzip is executed by the transport, not done once each by
  support and transport.
- HEAD/204/205/304 do not read the body source; the transport writes only the head. The support layer supplies `Content-Length`
  when the length is known and uncompressed; a buffering adapter (Jetty/Undertow) may materialize once for HEAD when the head
  lacks it, to keep the same length as GET, while the built-in streaming transport keeps
  chunked semantics with no CL.
- `framedBodyStream()` returns an **unlimited** transport stream; `HttpExchangeSupport` is responsible for
  wrapping it in `LimitedInputStream`. An adapter's own native limit (Undertow
  `MAX_ENTITY_SIZE`) is translated into `BodyTooLargeException` inside the stream returned by
  `framedBodyStream()`.
- method/path/queryParams/headers come from `RequestHead`; `RequestViewSupport` uniformly
  handles case-insensitive header lookup and the path var snapshot.
- `sslSession()` may be `null`, but `HttpRequest`'s TLS/body methods no longer provide defaults.

### 6.2 `ResponseHead`

Location: `com.jujin.freeway.http.ResponseHead` (public immutable value).

```java
public record ResponseHead(int status, boolean headRequest, List<Header> headers) {

    public record Header(String name, String value) {
        public Header {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
        }
    }

    public ResponseHead {
        headers = List.copyOf(headers);
    }

    /** 204 / 205 / 304 do not allow a body. */
    public boolean allowsBody();

    /** Whether body bytes are allowed on the wire: allowsBody && !headRequest. */
    public boolean sendsBodyBytes();

    public boolean hasHeader(String name);       // case-insensitive
    public String header(String name);           // first value
    public List<String> headers(String name);
    public ResponseHead withHeader(String name, String value);
    public ResponseHead withoutHeader(String name);
}
```

Design decisions:

- **`Content-Length` exists only in `headers`; there is no second `contentLength`
  field.** One fact has one home, avoiding "one value in the header, another in the field".
- **The head carries only `headRequest`, not the full `method`.** The only method the writer needs
  is the HEAD determination; putting method into the head would make the head carry a fact the writer
  never uses and that could disagree with "whether to suppress the body". `headRequest` is
  that minimal fact, and it cannot conflict with the semantics of `sendsBodyBytes()`.
- `ResponseHead` is the immutable snapshot at the commit point; headers changed by a handler
  after commit do not affect the already-written response.

### 6.3 `HttpExchangeSupport`

Location: `com.jujin.freeway.http.HttpExchangeSupport` (public final).

```java
public final class HttpExchangeSupport implements HttpExchange {

    public HttpExchangeSupport(
            HttpTransport transport,
            JsonCodec jsonCodec,
            Coercer coercer,
            long maxBodySize,
            HttpServerConfig.CompressionConfig compression,
            String correlationId) { ... }

    /** Engine/tests read a response header (Connection, X-Request-Id, etc.). */
    public String responseHeader(String name);

    /** The body limit in effect for the current request. */
    public long bodyLimit();
}
```

Behavioral contract:

- `maxBodySize` is a constructor parameter; `setMaxBodySize` changes only the current request's value;
  the base/reset pair of concepts does not exist.
- Body reading lazily creates
  `new LimitedInputStream(transport.framedBodyStream(), this::bodyLimit)`,
  so a filter raising the limit mid-request still takes effect immediately.
- `setStatus` validates `100..599`; `setHeader` / `addHeader` validate
  the RFC 7230 token and ISO-8859-1; after `committed == true`, header/status
  writes are no-ops and repeated `output` is ignored.
- `body(byte[])`: ensure `Date`; gzip decision; set
  `Content-Length` (the post-compression length); remove CL for bodyless statuses; build the head;
  call `transport.writeResponse(head, body)`.
- `body(InputStream, long)`: set CL when the length is known and not compressed;
  remove CL when gzipping or the length is unknown; call the transport's stream path.
- `file(...)`: set CL when the length is known and not compressed; remove CL when gzipping;
  call the transport's file path.
- `sse()`: set `Content-Type: text/event-stream`,
  `Cache-Control: no-cache`, `Date`, remove CL, and **write no
  `Connection`**; the HTTP/1 transport writes `Connection: close`.
- `pathVars()` returns an immutable copy; `setPathVars` replaces wholesale.
- `responseHeader(name)` is public: `Http1xSession`,
  `JettyHttpEngine`, and the testkit need to read it, whereas the `HttpResponse` interface does not expose
  "read a response header".

### 6.4 WebSocket SPI

```java
public interface WebSocketTransport {

    boolean isOpen();
    void sendText(String text) throws IOException;
    void sendBinary(byte[] data) throws IOException;
    void ping(byte[] data) throws IOException;

    /** reason has already been truncated to at most 123 bytes by Support. */
    void close(int code, String reason) throws IOException;
    void flush() throws IOException;

    default void sendTextBatch(List<String> texts) throws IOException {
        for (String text : texts) sendText(text);
        flush();
    }
}
```

```java
public final class WebSocketSessionSupport implements WebSocketSession {

    public WebSocketSessionSupport(
        WebSocketTransport transport,
        String correlationId,
        String method,
        String path,
        Map<String, String> pathVariables,
        Map<String, List<String>> queryParams,
        Map<String, List<String>> headers) { ... }
}
```

- The metadata snapshot logic moves over from `AbstractWebSocketSession` unchanged.
- `close(int, String)` performs the 123-byte UTF-8 truncation in Support and then delegates to
  the transport. The former divergence — the built-in engine "throws directly on over-long" while the adapter "truncates" —
  is thus unified as truncation.
- `sendTextBatch` delegates to `transport.sendTextBatch` in Support, keeping the built-in
  engine's single-flush optimization; adapters can use the default implementation.
- The built-in engine splits `WebSocketSessionImpl` into:
  - `WebSocketConnection implements WebSocketTransport`: frame writes, open/
    close state, closeCode/closeReason, read loop internal methods;
  - `WebSocketSessionSupport`: handed to `WebSocketEndpoint.open(...)`.
- `WebSocketReadLoop` now receives `WebSocketConnection` instead of
  `WebSocketSessionImpl`; `WebSocketUpgrade` creates
  `connection + support` and hands the support to the endpoint.

### 6.5 Visibility and Package Location

| Type | Location | Visibility | Rationale |
|---|---|---|---|
| `HttpTransport` | `http` | public | Implemented by ext adapters |
| `ResponseHead` | `http` | public | SPI signature |
| `HttpExchangeSupport` | `http` | public final | Constructed by ext adapters |
| `WebSocketTransport` | `http.websocket` | public | Implemented by ext adapters |
| `WebSocketSessionSupport` | `http.websocket` | public final | Constructed by ext adapters |
| `HttpTransportImpl` | `http.engine` | public final | Used by benchmarks across jars; `XImpl` naming |
| `HeaderStore` | `http.internal` | public (public methods) | Shared by Support and writers |
| `ResponseFraming` | `http.internal` | public | Shared by Support and writers |
| `Http1xResponseWriter` | `http.engine` | package-private | Built-in engine protocol detail |
| `Http2ResponseWriter` | `http.engine.http2` | public (current state) | Assembled outside the HTTP/2 package |
| `WebSocketConnection` | `http.engine.ws` | public (current state) | Used by `WebSocketUpgrade` in the same package; retained |

Why `HeaderStore` moves from `engine` to `internal`: `HttpExchangeSupport` lives in
`com.jujin.freeway.http` and cannot access `engine`'s package-private types. `internal`
is the repository's established "no stability promise" location; public is only so that sibling packages can assemble it.

## 7. Lifecycle Cost Assessment

### 7.1 Microbenchmark

Throwaway program: `LifecycleCost.java` (not in the repository), comparing two shapes:

- **pooled**: reuse `SupportLike`, and per request perform `meta.reset()`,
  `responseHeaders.clear()`, path vars clearing, `status/committed/body`
  field resets, and `maxBodySize` reset;
- **alloc**: per request `new SupportLike(...)`, constructing `ExchangeMetaImpl`,
  `HeaderStore`, and `Map.copyOf(pathVars)`;
- **built-in reset**: the full cost of the current `HttpContextImpl.reset(...)`,
  including request header copying, query parsing, and `new RequestBody`.

JDK 25, default `-Xms/-Xmx`, 7 rounds taking the minimum, 5,000,000 iterations per round;
a static `Object[]` sink prevents escape analysis from eliminating the allocation. Results of three runs:

| Path | First | Second | Third |
|---|---:|---:|---:|
| pooled support-state reset | 42.8 ns/op | 40.8 ns/op | 41.4 ns/op |
| per-request support alloc | 51.3 ns/op | 50.1 ns/op | 50.2 ns/op |
| built-in full reset | 349.4 ns/op | 351.5 ns/op | 355.6 ns/op |
| **delta (alloc - reset)** | **8.4 ns/op** | **9.3 ns/op** | **8.8 ns/op** |

### 7.2 Interpretation

- Creating support per request costs about **9 ns/op** more than "pooling + reset", about **2.5%**
  of the current full context reset cost (about 350 ns).
- Those 9 ns include the allocations of the `HttpExchangeSupport` object, `ExchangeMetaImpl`,
  `HeaderStore`, and the path-vars snapshot; `Instant.now()` inside `ExchangeMetaImpl`
  and correlation id generation are paid in both shapes.
- Creating the transport per request is another small object of the same order; compared with deleting
  the `ThreadLocal` pool, `reset`, and the pooled-state leakage risk, the cost is worth paying.

**JMH re-measurement** (`freeway-benchmark`, JDK 25, 3 s × 5 forks, full exchange
path, `-prof gc`):

| Shape | ns/op | B/op |
|---|---:|---:|
| pooled (reused transport + support) | 503.703 | 5360 |
| per-request (new transport + support per request) | 555.304 | 5544 |
| **delta** | **+51.6 (about +10%)** | **+184** |

A separate run without `-prof gc` gives 498.560 ± 39.860 and
562.292 ± 45.928 ns/op, a difference of about 64 ns/op, consistent with the table within error.
GC time is 21 ms per 3 s iteration with no measurable difference; the extra 184 B/op
is the entire cost of the support, `ExchangeMetaImpl`, `HeaderStore`, and snapshot objects.

At an estimated 100k req/s, 64 ns/op ≈ 6.4 ms CPU/second (under 1% of a single core);
at 1M req/s ≈ 64 ms/second (about 6% of a single core). This is a worst-case upper bound,
because a JMH single exchange has no real handler, body read, or downstream call.

### 7.3 Decision

**Adopt a new support per request + a new transport per request**. No `reset()` is kept:

- The "forgot to reset" failure mode does not exist;
- `maxBodySize`, principal, attributes, path vars, response headers, and status code
  are all discarded with the object;
- The "no state crossover across requests" assertion in `ContextContract` changes from "guaranteed by tests" to
  "guaranteed by shape"; the tests are still kept, as an interface contract;
- End to end it costs about 64 ns/op extra (about 10%), of which the pure support-state difference is about
  9 ns and the rest is transport/support-related allocation and a larger object graph; pooling would trade all of that
  back for a reset obligation, so the trade does not hold.

## 8. Behavior Changes (Must Go into the CHANGELOG)

1. **Response policy convergence**: status code range validation, header validation, `Date`, CL, gzip
   decision, and Vary now exist in only one copy, `HttpExchangeSupport`. Undertow / Jetty
   will start throwing `IllegalArgumentException` for illegal status codes, and will fill in `Date` uniformly
   (the adapter contract tests must confirm that the container does not add it a second time).
2. **Framework authority over `Content-Length`**: `body(byte[])` overwrites the value set by the handler with the actual byte count
   (the post-compression length when gzipping); `body(InputStream,
   len)` uses `len`, removing it when `len < 0`; `file` uses `length`;
   204/205/304 always remove it. The three engines no longer "some overwrite, some keep".
3. **Per-request support / transport**: `reset` no longer exists;
   the principal/attributes/limit/response-header reset obligations of `HttpContextImpl.reset`
   are gone. Tests that depend on `reset` (`keepAliveReset*`) are deleted or changed to
   "newly created means empty".
4. **SSE's `Connection`**: the shared layer no longer writes `Connection: keep-alive`.
   The HTTP/1 writer writes `Connection: close` for SSE; Undertow / Jetty leave it to
   the container; HTTP/2 filters `Connection`.
5. **Undertow native limit translation location**: `RequestTooBigException` →
   `BodyTooLargeException` moves into `UndertowHttpTransport.framedBodyStream()`;
   413 semantics are unchanged.
6. **gzip file path**: decision in Support, execution in Transport; the built-in engine keeps
   sendfile (only when uncompressed, body allowed, length ≥ 64 KiB), adapters use buffering.
7. **WebSocket close reason**: uniformly truncated to 123 bytes; the built-in engine no longer throws
   `IllegalArgumentException` for an over-long reason.
8. **`HttpContextImpl` is no longer the type handlers see**: handlers still see only
   `HttpExchange`; the built-in engine type is renamed `HttpTransportImpl`, and the ext/benchmark
   compile errors are the migration path.

## 9. File-by-File Change List

### 9.1 New in core

| File | Content |
|---|---|
| `freeway-http/src/main/java/com/jujin/freeway/http/HttpTransport.java` | The SPI from 6.1 |
| `freeway-http/src/main/java/com/jujin/freeway/http/ResponseHead.java` | The immutable snapshot from 6.2 |
| `freeway-http/src/main/java/com/jujin/freeway/http/HttpExchangeSupport.java` | The single policy implementation from 6.3 (about 350 lines) |
| `freeway-http/src/main/java/com/jujin/freeway/http/websocket/WebSocketTransport.java` | The frame SPI from 6.4 |
| `freeway-http/src/main/java/com/jujin/freeway/http/websocket/WebSocketSessionSupport.java` | The session snapshot + delegation from 6.4 |
| `freeway-http/src/main/java/com/jujin/freeway/http/internal/HeaderStore.java` | Moved in from `engine`, methods made public |
| `freeway-http/src/main/java/com/jujin/freeway/http/internal/ResponseFraming.java` | Moved in from `engine` |

### 9.2 Rewritten in core

| File | Change |
|---|---|
| `engine/HttpContextImpl.java` → `engine/HttpTransportImpl.java` | `implements HttpTransport`; drops codec/coercer/limit/status/headers/responded/compression; keeps only request reading, `RequestBody`, `rawOut`, `keepAlive`, `writer`, `fileSender`, TLS/remote; adds `drainRequestBody(long)`, `syncKeepAliveFromResponse(String)`, `isKeepAlive()` |
| `engine/HttpResponseWriter.java` | Parameters changed to `(ResponseHead head, boolean keepAlive)` / `(ResponseHead head, byte[]...)`; references to `HttpContextImpl` removed |
| `engine/Http1xResponseWriter.java` | Reads status/headers from the head; `writeHead` writes the status line, all headers, and framing (CL / chunked / Connection) in one pass; `writeBody` writes only the body; `end` writes the terminal chunk; `openSse` reuses `writeHead(head, false)` + `ChunkedOutputStream` |
| `engine/http2/Http2ResponseWriter.java` | Reads status/headers from the head; keeps forbidden-header filtering, multi-value `Set-Cookie`, and bodyless CL removal |
| `engine/Http1xSession.java` | Per request `new HttpTransportImpl(...)`; `new HttpExchangeSupport(...)`; body drain uses `transport.drainRequestBody(support.bodyLimit())`; `HttpSession.echoRequestId(support)` |
| `engine/Http2Session.java` | Per stream `new HttpTransportImpl(...)` + `new HttpExchangeSupport(...)`; no longer construct first and then `reset` |
| `engine/HttpSession.java` | `echoRequestId(HttpExchange)` |
| `engine/RequestBody.java` | Removes limit/cached ownership; keeps chunked/fixed framing; `drain(long limit)`; `stream()` returns an unlimited framed stream |
| `engine/ws/WebSocketSessionImpl.java` → `engine/ws/WebSocketConnection.java` | Keeps only frame/connection state; implements `WebSocketTransport` |
| `engine/ws/WebSocketReadLoop.java` | Parameter `WebSocketSessionImpl` → `WebSocketConnection` |
| `engine/WebSocketUpgrade.java` | Creates `WebSocketConnection` + `WebSocketSessionSupport`; the endpoint receives the support; the read loop receives the connection |
| `ExchangeMetaImpl.java` | Deletes `reset()` (its only caller is gone) |
| `engine/package-info.java`, `HttpExchange.java`, `HttpEngine.java`, `internal/HttpHeaders.java` | Docs/comments synchronized; the reset obligation of `setMaxBodySize` removed; header validation methods moved into `HttpHeaders` |

### 9.3 Deleted from core

| File | Reason |
|---|---|
| `http/AbstractHttpContext.java` | Replaced by `HttpExchangeSupport` |
| `http/websocket/AbstractWebSocketSession.java` | Replaced by `WebSocketSessionSupport` |
| `engine/Headers.java` | Moved into `internal` and renamed `HeaderStore` |
| `engine/ResponseFraming.java` | Moved into `internal` |

### 9.4 core tests

| File | Change |
|---|---|
| `StubHttpExchange.java` → `TestExchange.java` | Composes `TestTransport` + the real `HttpExchangeSupport`; keeps `new TestExchange(...)`, the `requestHeader/requestBody/queryParam` builders, and the `status()/responseBody()/responseHeader()` accessors; internally no longer `extends` any framework type |
| `HttpExchangeTest.java` | `StubHttpExchange` → `TestExchange`; header validation switched to `HttpHeaders.requireValidName/requireValidValue`; `exchangeMetaResetClearsPerRequestState` deleted (`reset` is gone), and "a new support starts with empty state" added |
| `HttpContextImplTest.java` → `HttpExchangeSupportTest.java` + `Http1xTransportTest.java` | Policy tests record `ResponseHead/body` with `TestTransport`; wire tests drive `Http1xResponseWriter`/`HttpTransportImpl` with a `ByteArrayOutputStream`; the three `keepAliveReset*` cases are deleted (the lifecycle changed) |
| `Http2ResponseWriterTest.java` | Constructs `ResponseHead` directly instead of constructing a context |
| `BodyLimitParityTest.java` | Switched to `TestExchange`; comments changed to "shared support + a single `LimitedInputStream`" |
| The other 9 tests referencing `StubHttpExchange` | `StubHttpExchange` → `TestExchange` (about 80 mechanical replacements); if `TestExchange` implements `HttpExchange` by delegation, call shapes stay basically unchanged |
| `websocket/AbstractWebSocketSessionTest.java` → `WebSocketSessionSupportTest.java` | Immutable snapshot, close reason truncation, frame delegation, `sendTextBatch` delegation |
| New `HttpExchangeSupportTest` | Status code validation, header validation, Vary, 415, body limits and dynamic raising, 204/304 CL, gzip decision, HEAD, repeated output, Date |
| New `Http1xTransportTest` | chunked, known-length, HEAD, 204/304, streaming gzip, length mismatch, sendfile, SSE, `Connection` |

Implementation decision for `TestExchange` (Option A):

- `TestExchange implements HttpExchange`, delegating all `HttpExchange/HttpRequest/
  HttpResponse` methods to the real internal `HttpExchangeSupport`, with request/response
  mechanisms provided by `TestTransport`. It is still **composition** (fields + delegation), not
  inheritance; about 80 call sites keep the `ctx.status()` shape, and review focuses on whether the assertions
  go through the real support.
- `TestTransport` records the submitted `ResponseHead` and body, and provides a configurable
  request snapshot (method/path/query/headers/body stream/TLS/remote).
- There is no second "fake context" implementation that bypasses the shared policy; tests must assert the behavior of
  the real `HttpExchangeSupport`.

### 9.5 ext HTTP adapters

| File | Change |
|---|---|
| `UndertowHttpExchange.java` → `UndertowHttpTransport.java` | `implements HttpTransport`; keeps only Undertow's method/path/query/headers/body stream/TLS/remote, `writeResponse`, `writeResponseFile`, `openSse`; `RequestTooBigException` translated inside `framedBodyStream()`; removes `reset`, `compression`, `responseStatus`, `responded`, `cachedBody`, `responseHeaders` |
| `UndertowHttpEngine.java` | In dispatch, `new UndertowHttpTransport(exchange)` + `new HttpExchangeSupport(...)`; removes the `ThreadLocal` pool, `reset`, `setCompression`; `setHeader("X-Request-Id")` goes through support; handler exceptions use `ctx.isCommitted()` |
| `JettyHttpExchange.java` → `JettyHttpTransport.java` | Same as above; `writeResponse` uses request/response/callback; `openSse` keeps the current output stream |
| `JettyHttpEngine.java` | Same as above; `ctx.responded()` → `ctx.isCommitted()`; pool/reset removed |
| `freeway-http-adapter-testkit/ContextContract.java` | Comments changed from `AbstractHttpContext.readBody` to `HttpExchangeSupport` + `LimitedInputStream`; assertions unchanged |

The adapter's `writeResponse(ResponseHead head, byte[] body)`:

```java
exchange.setStatusCode(head.status());
head.headers().forEach(h -> {
    if (!"Content-Length".equalsIgnoreCase(h.name())) {
        exchange.getResponseHeaders().add(h.name(), h.value());
    }
});
if (head.hasHeader("Content-Length")) {
    exchange.setResponseContentLength(Long.parseLong(head.header("Content-Length")));
} else {
    exchange.getResponseHeaders().remove(Headers.CONTENT_LENGTH);
}
if (head.sendsBodyBytes() && body.length > 0) {
    exchange.getResponseSender().send(ByteBuffer.wrap(body));
} else {
    exchange.endExchange();
}
```

(The Jetty version corresponds to `response.setStatus` / `response.getHeaders().add` /
`response.write(true, ByteBuffer.wrap(body), callback)`; when there is no body,
`callback.succeeded()`. `Content-Length` is likewise excluded from the header loop
and written separately, to avoid duplication.)

### 9.6 ext WebSocket adapters

| File | Change |
|---|---|
| `UndertowWebSocketSession.java` → `UndertowWebSocketTransport.java` | `implements WebSocketTransport`; keeps channel, maxMessageSize, sendLock, localCloseRequested, listener; `open(WebSocketListener, WebSocketSession)` starts the receive listener and calls `onOpen(session)` |
| `UndertowHttpEngine.handleWebSocket` | Creates the transport + `WebSocketSessionSupport`; the endpoint receives the support |
| `JettyWebSocketSession.java` → `JettyWebSocketTransport.java` | `implements WebSocketTransport`; keeps session, sendLock, localCloseRequested; `open(WebSocketListener, WebSocketSession)` |
| `JettyWebSocketBridge.java` | Holds support + transport; `onWebSocketOpen` creates both; close/errors go through support; `localCloseRequested` goes through the transport |
| `WebSocketUpgradeContract` | Assertions unchanged; one close reason truncation case added (if not already covered by the existing contract) |

### 9.7 ext testkit / benchmark / docs

| File | Change |
|---|---|
| `freeway-benchmark/.../FilterChainBenchmark.java` | `HttpContextImpl` → `HttpTransportImpl` + `HttpExchangeSupport`; construct per op (or the fixture's `newExchange()`), no longer calling the package-private `reset` |
| `freeway-benchmark/.../HttpExchangeLookupBenchmark.java` | Same as above; construct once in setup, and `@Benchmark` only performs read lookups (constructing in setup does not affect lookup cost) |
| `freeway-benchmark/.../HttpExchangeOutputBenchmark.java` | Same as above; the output path constructs a new support each time, avoiding reuse of `committed` state |
| `freeway-core/AGENTS.md` | Removes the "implementation shared with third-party adapters" exception; the adapter regression entry is changed to `HttpExchangeSupport` + `LimitedInputStream` |
| `freeway-core/docs/architecture.md` | HTTP seam diagram: `HttpExchangeSupport` + `HttpTransport`; explains how adapters plug in |
| `freeway-core/docs/developer-guide.md` | Engine list: `HttpContextImpl` → `HttpTransportImpl` + `HttpExchangeSupport`; `WebSocketSessionImpl` → `WebSocketConnection` + `WebSocketSessionSupport` |
| `freeway-core/skills/freeway-dev/references/http.md` | Custom engine integration notes (SPI methods, Support construction, no more inheritance) |
| `freeway-core/CHANGELOG.md` | Breaking-change table: the two base classes deleted, SPI names, the reset obligation gone, behavior changes 1–8 |
| `freeway-ext/CHANGELOG.md` | Migration of the two adapters, benchmark synchronization |

## 10. Test Plan

### 10.1 core

```bash
mvn -pl freeway-http -am test
mvn test
```

Focus of additions/migrations:

- `HttpExchangeSupportTest`: pure policy, `TestTransport` records calls; asserts the `ResponseHead`'s status/headers, CL rules, gzip decision, Date, 415, 413, Vary, HEAD, repeated output.
- `Http1xTransportTest`: real byte streams; chunked/CL/Connection, HEAD, 204/304, streaming gzip, length mismatch, sendfile, SSE.
- `Http2ResponseWriterTest`: `ResponseHead` directly; forbidden headers, multi-value `Set-Cookie`, bodyless CL.
- `BodyLimitParityTest`: the body path of `TestExchange` and `LimitedInputStream` remain byte-for-byte identical; dynamically raising the limit still takes effect.
- `WebSocketSessionSupportTest`: immutable snapshot, close reason truncation, `sendTextBatch` delegation.

### 10.2 ext

```bash
cd ../freeway && mvn -DskipTests install
cd ../freeway-ext
mvn -o clean test
mvn -o spotless:check
```

Must all be green:

- `ContextContract` (TLS, no state crossover across requests, 413, 204 CL)
- `CompressionContract`
- `HeaderBudgetContract`
- `RemoteRpcContract`
- `WebSocketUpgradeContract`
- `freeway-benchmark`, at least `mvn -o -pl freeway-benchmark -am test-compile`

### 10.3 Negative verification (per repository convention)

- Remove `HttpExchangeSupport`'s `LimitedInputStream` → the 413 / parity
  tests must go red.
- Remove bodyless CL removal → the 204/304 tests must go red.
- Remove `Http2ResponseWriter`'s forbidden-header filtering →
  `Http2ResponseWriterTest` must go red.
- Remove the truncation in `WebSocketSessionSupport.close` → the close reason test
  must go red.
- Break `Http1xResponseWriter`'s chunked derivation → the streaming tests must go red.

### 10.4 Build order (to avoid a stale `target/` masking ABI)

After changing core, run `mvn -DskipTests install`, then in ext run
`mvn -o clean test`. ext's incremental compilation may reuse old
classes when the core ABI changes; `clean` is the only proof.

## 11. Implementation Order and Commits

### Core (3 commits)

1. **Add the skeleton, do not switch engines**
   - Add `HttpTransport`, `ResponseHead`, `HttpExchangeSupport`,
     `WebSocketTransport`, `WebSocketSessionSupport`;
   - Move `HeaderStore` / `ResponseFraming` into `internal`;
   - Add `HttpExchangeSupportTest` and other unit tests;
   - `mvn -pl freeway-http -am test` green.
2. **Switch the built-in engine**
   - `HttpContextImpl` → `HttpTransportImpl`;
   - Rework writers / sessions / WebSocket;
   - Delete the two base classes and `ExchangeMetaImpl.reset`;
   - Migrate `StubHttpExchange` and the tests;
   - `mvn test` green.
3. **Docs and CHANGELOG**
   - AGENTS / ARCHITECTURE / DEVELOPER-GUIDE / skill / CHANGELOG;
   - `mvn test` green.

### Ext (3 commits)

4. **HTTP adapters**
   - Undertow/Jetty transport + engine dispatch;
   - testkit comments;
   - `mvn -o clean test` + `spotless:check` green.
5. **WebSocket adapters**
   - The two WS transports + bridge/engine wiring;
   - `mvn -o clean test` green.
6. **benchmark + ext CHANGELOG**
   - The three benchmark files;
   - `mvn -o -pl freeway-benchmark -am test-compile` green.

Every commit requires its corresponding build to be green; core `install`s first, ext must `clean`.

## 12. Risks and Mitigations

| Risk | Location | Mitigation |
|---|---|---|
| Commit semantics change (status/headers/committed ownership) | Support + the three transports | Closed: `HttpTransport.isCommitted()` is the single fact; failures before commit roll back, failures after commit are handled as transport failures |
| keep-alive and body drain | `RequestBody` / `Http1xSession` | `drain(limit)` keeps "over-limit means no reuse"; `BodyLimitParityTest` |
| Jetty callback completion determination | `JettyHttpTransport` / `JettyHttpEngine` | `isCommitted()` replaces the package-private `responded()`; `callback.succeeded()` when there is no body |
| Undertow async sender and SSE | `UndertowHttpTransport` | Keeps the existing `SseOutputStream` and callbacks; only the status/header source changes |
| Duplicate Date | adapter containers | Closed: Jetty's automatic Date is disabled; `ContextContract` asserts there is exactly one `Date` |
| HEAD's CL | the three writers | Closed: a known length keeps CL; an unknown length gets the actual CL filled in by the buffering transport after materialization, while the streaming transport uses chunked/no CL; adapters do not add 0 |
| gzip streaming length | built-in writer / adapter default | With gzip the head has no CL; HTTP/1 chunked, HTTP/2 no CL; the default buffering path fills in the post-compression CL |
| Decoupling the WebSocket read loop from the session | `WebSocketConnection` / `WebSocketReadLoop` | Change built-in WS first, then the adapters; `WebSocketUpgradeContract` |
| close reason behavior change | `WebSocketSessionSupport` | Truncate uniformly; add a contract case |
| No more package-private `reset` in benchmarks | ext benchmarks | Construct a new support per op; the benchmark itself is the true cost of the per-request lifecycle |
| `TestExchange` has many delegating methods | core tests | Only test code changes; use generated/mechanical delegation, and review focuses on whether assertions go through the real support |

## 13. Effort Estimate

| Part | Files | Estimated change |
|---|---:|---:|
| core additions/rewrites | ~20 | 1200–1500 lines |
| core test migration | ~15 | 400–600 lines |
| ext HTTP adapters | 4 | 500–650 lines |
| ext WS adapters | 4 | 250–350 lines |
| benchmark | 3 | 150–250 lines |
| docs / CHANGELOG | 7 | 200–300 lines |

Net code volume is roughly flat or slightly lower; **adapter classes will shrink noticeably**, because
gzip/status/header/body limit/JSON all move up into Support. The real payoff is not line count:

- The `extends` exception disappears from AGENTS;
- "Reset per request" becomes a failure mode that does not exist;
- The three engines' 413 / gzip / Date / CL / header validation go from "three copies that happen to agree"
  to "structurally one copy";
- Third-party engines only need to implement one narrow interface, and no longer need to understand or inherit the core base classes.

## 14. Settled Decisions

1. **SPI naming**: adopt `HttpTransport` / `WebSocketTransport` /
   `HttpExchangeSupport` / `WebSocketSessionSupport`. The built-in HTTP context
   is renamed `HttpTransportImpl` (`XImpl`: engine-internal, no substitution point).
2. **`ResponseHead` shape**: `record ResponseHead(int status,
   boolean headRequest, List<Header> headers)`; `Content-Length` is only in
   `headers`; `allowsBody()` / `sendsBodyBytes()` are derived from the status and
   `headRequest`. It does not carry the full `method`.
3. **transport lifecycle**: per-request transport + per-request support. The basis is
   the JMH data in Section 7: about 64 ns/op more end to end (about 10%), in exchange for deleting
   `reset`/`ThreadLocal` pools and the state-crosstalk failure mode.
4. **`TestExchange` shape**: Option A — implement `HttpExchange` and delegate to the real
   `HttpExchangeSupport`, composing `TestTransport` internally.
5. **Implementation granularity**: core and ext are completed in the same round; core `install`s first, and ext
   must be built with `clean`; no "adapters migrate later" tail is left behind.

## 15. First-Round Implementation and Acceptance Record (2026-11-12)

Completed in the order of Section 11; all six parts (core skeleton/switching engines/docs, ext
HTTP/WS/benchmark) landed; the first round was 3 core commits + 2 ext commits.

**Build and tests**

| Step | Result |
|---|---|
| core `mvn -pl freeway-http -am test` | 504 green |
| core `mvn test` (all modules) | 418 / 320 / 125 / 504 / 531 / 128 / 359 all green |
| core `mvn -DskipTests -Dgpg.skip=true install` | Success (no gpg on this machine, signing skipped) |
| ext `mvn clean test` (clean is the only ABI proof) | 212 green (Kafka 5 + Consul 2 skipped per environment variables) |
| ext `mvn spotless:check` | Passed |

Contracts run: `ContextContract`, `CompressionContract`,
`HeaderBudgetContract`, `RemoteRpcContract`, `WebSocketUpgradeContract`
each inherited and passed on both Undertow and Jetty; in addition the TLS/HTTP2/dispatch-io/
transport-limits probes were all green.

**Negative verification (Section 10.3, break → red → restore, item by item)**

| Break | Result |
|---|---|
| `HttpExchangeSupport` loses the `LimitedInputStream` wrapping | `BodyLimitParityTest` 3 red + `HttpExchangeSupportBodyLimitTest` 5 red |
| `body(byte[])` no longer removes `Content-Length` for bodyless statuses | `HttpExchangeSupportTest` 2 red |
| `Http2ResponseWriter` loses forbidden-header filtering | `Http2ResponseWriterTest` 2 red |
| `WebSocketSessionSupport.close` does not truncate the reason | `WebSocketSessionSupportTest` 1 red |
| `Http1xResponseWriter`'s chunked derivation is hard-coded to false | `Http1xTransportTest` 3 red |

After restoring, the full core/ext test suites were green again, with no leftover break markers in the code.

**Three plan corrections made during implementation** (unforeseen by the plan, forced out by tests):

1. **The conflict between framework authority over `Content-Length` and static-file HEAD**:
   `body(byte[])` now overwrites the handler-set value with the actual submitted byte count, while
   `StaticResourceMount`'s HEAD branch used to "set the `Content-Length` header +
   `output(new byte[0])`" — which would be overwritten to 0. Changed to hand the length to
   `output(nullInputStream(), length)` (passing -1 for an unknown length), and to make
   **the transport not read the response source when `sendsBodyBytes()==false`** (the SPI default
   implementation, the built-in transport, and `TestTransport` all agree): HEAD no longer opens the file,
   and the HEAD of a 60 MB file only reads metadata.
2. **The drain limit must subtract bytes already read**: support's limiter and
   `RequestBody.drain(limit)` are two read points; using the "whole-segment limit" for drain would let a
   handler read more after reaching the limit. `RequestBody` now uses a counting stream to record consumed bytes,
   and the drain allowance is `limit - consumed`.
3. **Where handler exceptions land**: Undertow/Jetty used to use the container's wire state
   (`isResponseStarted`/`isCommitted`) to decide whether a 500 could be written; under the new shape
   "committed but no wire bytes yet" (such as 204) would be misjudged. Changed to check
   `ctx.isCommitted()` first: once committed, leave the response alone; only write 500 when uncommitted.

**Two outstanding items recorded after the first round**:

- In the first round `WebSocketUpgradeContract` covered only the handshake (101) and had no close reason case.
  Truncation now exists in exactly one place, `WebSocketSessionSupport.close`, and the transports of the two adapters
  already receive the truncated value (each no longer implements it separately, and the
  "built-in throws / adapter truncates" divergence is gone), pinned by
  the 4 cases of `WebSocketSessionSupportTest`. **The audit revision has added the end-to-end
  case**: `handlerFailureClosesWithATruncatedReason`, inherited by both adapters.
- The first round was committed as 3 core + 2 ext commits; this round's audit revisions are not yet committed,
  and the working tree is kept in a reviewable state.

## 16. Audit Revisions (2026-11-12)

The item-by-item audit after the first acceptance round found that green tests did not cover the seam's boundaries. This round's revisions are as follows:

### 16.1 Commit fact belongs to the transport

`HttpTransport.isCommitted()` is the single commit fact: the transport flips it before writing the first
head byte, and `HttpExchangeSupport.isCommitted()` only reads this fact, no longer
setting `committed` itself first. Output that fails before commit discards the prepared status/headers
and rethrows, so `HttpServer` can write a 500; a failure after commit is handled as a transport failure.
A missing file/file-open failure and a stream read failure before commit no longer drop the connection or hang. Regressions:
`HttpExchangeSupportTest.fileOpenFailureLeavesTheExchangeRetryable`,
`ContextContract.missingFileBecomesAServerErrorInsteadOfAnEmptyResponse`.

### 16.2 Framing belongs to the transport

The default (buffering) transport fills in the actual `Content-Length` for the materialized body, including
HEAD; the streaming transport keeps chunked semantics with no CL. The HTTP/1.1 adapter adds
`Transfer-Encoding: chunked` only when the response still has no CL (such as SSE); HTTP/2 adds nothing, and filters
connection-level fields such as `Connection`/Keep-Alive/Upgrade/TE. support uniformly filters the handler-set
`Transfer-Encoding`. This makes the unknown-length response headers for HEAD and GET consistent, and also eliminates
the defect where a container adds `Content-Length: 0`. Regressions:
`ContextContract.unknownLengthBodiesFrameHeadAndGetTheSameWay`,
`handlerCannotInjectTransferEncoding`,
`CompressionContract.compressesLargeStreamingResponse`.

### 16.3 Adapter contracts

- Jetty's automatic Date is disabled (`HttpConfiguration.setSendDateHeader(false)`),
  covered by `ContextContract.responsesCarryExactlyOneDateHeader`.
- Internal WebSocket error closes in Undertow/Jetty go through
  `WebSocketSessionSupport.close`, no longer bypassing the 123-byte truncation;
  covered by `handlerFailureClosesWithATruncatedReason`.
- `HttpExchangeSupport.setStatus` is a no-op after commit, consistent with headers.

### 16.4 Naming and cleanup

- The response lifecycle uniformly uses **committed**: `HttpTransport.isCommitted()`,
  `HttpResponse.isCommitted()`, `HttpExchangeSupport.isCommitted()`, with the rollback helper
  `resetIfUncommitted()`; `HttpResponse.isCommitted()` is abstract and no longer falls back
  to a default false. `HttpExchangeSupport`'s private `head()` becomes `responseHead()`, to avoid confusion
  with HTTP HEAD. The old `responded()`/`responded` field in the migration table is only a historical code name and does not represent the current API.
- The HTTP/2 stream's `headersSent`/`isResponseCommitted()` are unified as
  `committed`/`isCommitted()`; the H2 transport's `isCommitted()` reads that stream's
  fact directly and no longer holds a second commit determination.
- All response modification methods keep chained returns: `addVary` also returns `HttpResponse`.
- `HttpTransport.bodyStream()` becomes `framedBodyStream()`, to distinguish it from the handler-side
  `HttpRequest.bodyStream()` (already limited/cached); the transport returns an unlimited
  protocol-framed stream.
- `HttpResponse` no longer provides body default implementations that would silently break length/gzip semantics:
  `body(InputStream, long)` and both `file` methods are abstract contracts, handled by
  `HttpExchangeSupport` (or a custom implementation). `allowsResponseBody()` is unified as
  `allowsBody()`; the 204/205/304 rule is centralized in `HttpStatus.allowsBody(int)`, and
  both `HttpResponse` and `ResponseHead` delegate to it.
- `http.internal.Headers` → `HeaderStore`, distinguished from the
  syntax helper `HttpHeaders`.
- Remove the duplicate rule in `HttpResponse.suppressBodyBytes` / `ResponseFraming.suppressBodyBytes`
  (production already uses `ResponseHead.sendsBodyBytes`), as well as the caller-less
  `HeaderStore.setValueIfPresent/getAll` and `ExchangeMetaImpl.setCorrelationId`.
- `WebSocketReadLoop.readLoop` loses its unused `OutputStream` parameter.
- Synchronize old lifecycle wording that still describes "reset/reuse" in `HttpExchange`,
  `FreewayHttpEngineTest`, and `BodyLimitParityTest`.
- `HttpResponseWriter`'s actual shape is that the writer holds keepAlive when constructed, and methods receive only
  `ResponseHead` (not the `(head, keepAlive)` parameter written in Section 9.2); one
  writer per response, and keepAlive is a lifecycle fact of the writer.

### 16.5 Verification after the revisions

| Step | Result |
|---|---|
| core `mvn test` (all modules) | 418 / 327 / 125 / 521 / 538 / 128 / 361 all green |
| core `mvn -pl freeway-http -am test` | 521 green |
| ext `mvn -o clean test` | 228 green (51 / 47 / 51 / 17 / 21 / 41; 7 skipped) |
| ext `mvn -o spotless:check` | Passed |

New contract cases: Date, unknown-length HEAD, handler TE, and missing-file 500 in `ContextContract`;
streaming gzip in `CompressionContract` (the buffering transport's CL matches the compressed length); the error close reason in `WebSocketUpgradeContract`;
the two adapters' HTTP/2 contracts (a single Date, hop-by-hop field filtering, consistent CL for unknown-length HEAD/GET);
`ContextContract`'s GET/HEAD framing for file responses; in core, `HttpExchangeSupportTest`'s rollback/status guard/TE,
`Http1xTransportTest`'s length mismatch/
205/HEAD, `HttpTransportImplTest`'s committed flag, and `HttpServerStandaloneTest`'s missing-file 500.

## 17. Compositional Refactoring of the HTTP/2 Frame Family (2026-11-13)

The `BaseFrame` abstract class has been deleted and replaced by a package-private sealed `Http2Frame` interface:

- Each frame implements `Http2Frame` and **composes** a `FrameHeader` field;
- `Http2Frame` provides `header()`, `writeTo()`, and a default `encode()`;
- `FrameSerializer`, `Http2Connection`, and `Http2Stream` all return/accept
  `Http2Frame`;
- Dispatch is now a sealed pattern switch, and the production path no longer has
  casts like `(SettingsFrame) frame`;
- All frame types are listed explicitly in `permits`, so the compiler forces dispatch to be updated when a frame type is added.

This is a change of design taste/type safety, **not a performance optimization**. The pattern switch still performs type discrimination,
and sealed only provides compile-time exhaustiveness; multiple implementations of the interface's `header()` may also turn a call site from monomorphic into
megamorphic. Performance verification should be done separately with JMH/end-to-end benchmarks; what can genuinely be optimized is `encode()`'s
`ByteArrayOutputStream`, inbound payload copying, and HPACK/IO, not removing the cast.
