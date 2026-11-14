# Failure Taxonomy

Freeway throws a small set of public exception types. The table is the
classification contract: use it instead of inferring intent from a class name.
"Retryable?" means *the framework itself may retry*; an application can always
choose to retry an operation it knows is idempotent.

| Exception | Module | Meaning | Retryable? | Outcome known? | Boundary |
|---|---|---|---|---|---|
| `AmbiguousBindingException` | ioc | Two eligible bindings and no primary | no | yes (nothing ran) | startup / composition |
| `MissingBindingException` | ioc | No binding for a required type | no | yes (nothing ran) | startup / composition |
| `UnknownSymbolException` | ioc | A required configuration key has no value | no | yes (nothing ran) | startup |
| `ValidationException` | http | Request body or parameter validation failed | no | yes (request rejected) | HTTP 400 `validation_failed` |
| `BodyTooLargeException` | http | Request body exceeded the configured limit | no | yes (request rejected) | HTTP 413 `payload_too_large` |
| `MultipartException` | http | Malformed multipart request | no | yes (request rejected) | HTTP 400 `invalid_multipart` |
| `UnsupportedMediaTypeException` | http | Content type not accepted by the handler | no | yes (request rejected) | HTTP 415 `unsupported_media_type` |
| `SqlException` | db | Database access failed (syntax, constraint, connection, transaction) | no | cause-dependent | caller / transaction |
| `FlowException` | flow | A graph run failed at execution time | no | depends on the failed node | caller |
| `RpcException` | cloud | Remote invocation failed; see `Kind` below | `retryable()` | `outcomeUnknown()` | caller / resilience policy |
| `RemoteInvocationException` | cloud | The remote handler threw a business exception | no | yes (peer answered) | cause of `RpcException.Kind.BUSINESS` |
| `StorageException` | cloud | Object storage failed (I/O, invalid bucket/key) | no | cause-dependent | caller |

Graph *configuration* errors (unknown graph, bad task reference, ambiguous
handler) are `IllegalArgumentException` / `IllegalStateException` at load, not
`FlowException`; `FlowException` is reserved for execution.

## `RpcException.Kind`

| Kind | Meaning | `retryable()` | `outcomeUnknown()` |
|---|---|---|---|
| `NO_INSTANCE` | Discovery found no live instance | false | false |
| `CIRCUIT_OPEN` | The per-service breaker is open; nothing was sent | false | false |
| `RATE_LIMITED` | The local limiter refused; nothing was sent | false | false |
| `CONNECT` | Connect refused/timeout; the request never left the process | true | false |
| `TIMEOUT` | No reply within the budget; the peer may have applied it | true | true |
| `TRANSPORT` | Mid-flight I/O failure (reset, broken pipe, TLS) | true | true |
| `INTERRUPTED` | The caller asked to stop | false | false |
| `HTTP` | Peer answered an unmapped status | depends on status (5xx yes, 4xx no) | 5xx yes |
| `BUSINESS` | Remote handler threw; cause is `RemoteInvocationException` | false | false |
| `REPLY_UNREADABLE` | Reply did not decode into the declared type | false | false |
| `REJECTED` | Peer refused the call's shape (version/mapping/method) | false | false |
| `DISPATCH` | Local failure before the transport (bad URL/header, discovery bug) | false | false |
| `OTHER` | Mapped failure with no more specific kind | by construction | by construction |

The RPC retry loop uses these two methods directly: transport/5xx failures are
retried only when `RpcRequest.idempotent()` is true and `outcomeUnknown()` says
a replay is safe; local rejections and interrupted calls fail immediately. HTTP
mapping is separate: `ErrorMapper.defaults()` maps the four HTTP exceptions
above and anything else becomes the server's final 500, after application
mappers have had their say.

## Rules

- **Do not classify by cause type.** A `SocketTimeoutException` can mean a
  connect timeout (nothing sent) or a response timeout (outcome unknown); the
  RPC layer has already made that distinction.
- **No framework retry outside RPC.** `SqlException`, `FlowException` and
  `StorageException` carry no retry decision; retry only at a layer that knows
  the operation is idempotent, and only for the cause that justifies it.
- **A failure that changes configuration must be loud at startup**, not a
  defensive fallback at request time (`UnknownSymbolException` and the
  composition exceptions are the shape).
