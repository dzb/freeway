# Freeway 1.3.9 Release Notes

> Version: 1.3.9 ｜ Highlight: **the message domain takes shape — broadcast, request-reply and streams as three first-class channels**

## Headline: the message domain

Until now freeway had one asynchronous primitive: the broadcast EventBus. 1.3.9 completes the
picture with three complementary channels, each with its own grammar:

- **Broadcast** (`EventBus.publish`) — facts about what happened; past-tense topics
  (`user.created`); best-effort delivery with subscriber isolation; Defer-buffered for
  transactional outbox semantics.
- **Request-reply** (`CallBus`, new in freeway-ioc) — commands and queries;
  `mapping.methodName` topics (`user.getUser`). Providers register a plain object whose public
  methods become hot-swappable slots; consumers inject a JDK dynamic proxy
  (`consumer(mapping, Api.class)`) or call directly (`call(topic, List args[, Duration])`).
  Positional argument encoding — no `-parameters` dependency. Failures reach the caller:
  RuntimeExceptions as-is, checked ones via standard join/get wrapping; a missing provider is a
  `DeadCallException` (the reply-side counterpart of DeadEvent) that proxies degrade to
  interface default methods. Calls dispatch inline: inside a DB transaction a call behaves
  exactly like a local method call — buffering it until commit would deadlock the joining
  caller, so post-commit side effects belong on the broadcast channel, where Defer buffering
  already lives.
- **Streams** (`EventBus.stream(Class)` / `stream(String)`) — a `java.util.concurrent.Flow`
  publisher view over the same subscriptions as broadcast. Zero external dependencies: the
  spec has been part of the JDK since 9. Cold-lazy attachment, overflow-drop backpressure that
  never stalls bus dispatch, cancel-detaches semantics, close-completes-all lifecycle.

## Cross-cutting

- **Call-chain advice** (`CallBus.advise([selector,] advice)`) — around-advice over the call
  chain, mirroring the container's `MethodAdvice`. Value-space short-circuiting: return without
  proceeding to answer from cache, throw to trip a breaker; selectors scope policies to topic
  prefixes. Advices see business exceptions, not reflection artifacts.
- **SSE pump** (`SseEmitter.from(publisher[, mapper])`) — wire any `Flow.Publisher` straight
  into an SSE response. One-in-flight demand propagates TCP write speed upstream end-to-end;
  the pump blocks the calling virtual thread until the source ends so try-with-resources
  closes only after the stream does; client disconnects wake the pump through a latch and
  cancel the upstream.

## Governance

The two topic namespaces are kept apart by grammar rather than machinery: facts are past tense,
calls are imperative method pairs. The split is documented at both class headers and locked by
regression tests (a live stream suppresses DeadEvents for its topic; cache short-circuits do
not count as served).

## Modules

- **freeway-ioc** — `EventBus.stream()`, new `CallBus` + `DeadCallException`, `Await` test
  helper consolidating polling loops.
- **freeway-http** — `SseEmitter.from(...)` reactive pump.
- **freeway-boot / freeway-db / freeway-flow / freeway-cloud** — unchanged; all compose with
  the message domain as-is (workflow nodes and WS handlers inject `CallBus`; DB transactions
  provide the Defer scopes the grammar builds on).
