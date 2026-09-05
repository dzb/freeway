# Freeway 1.5.0 Release Notes

> Version: 1.5.0 ｜ Highlight: **the Default/Impl naming criterion is settled and every replaceable default now lives beside its interface; the cloud event mesh hardens (deny-by-default + hello-first) and the module internals draw a clean "no stability promise" line**

1.5.0 is the naming and structure settlement release, delivered on top of
two audit passes (package structure + cloud). The Default-vs-Impl rule is
finalized around *outside substitutability*: `XDefault` names the framework's
default implementation of a role the outside can replace (`.primary()`
binding, constructor selection, or config activation) — `XImpl` marks the
absence of that substitutability. All twelve cloud defaults moved out of
`cloud.internal` into their feature packages, and the module internals
(engine visibility, `internal` packages) now say "no stability promise"
explicitly instead of leaving it to convention. Alongside the naming work the
cloud event mesh gets real security hardening and the whole suite is green at
1,870 tests across the 8 core modules. freeway-ext is released in lockstep at
1.5.0.

## Breaking

- **`XDefault`/`XImpl` settlement — net renames vs 1.4.0** —
  `HttpContextDefault`→`HttpContextImpl` (engine context, `freeway-http`),
  `LoggerSourceDefault`→`LoggerSourceImpl` (`freeway-ioc`),
  `PooledConnectionDefault`→`PooledConnectionImpl` (`freeway-db`),
  `ProxyFactoryDefault`→`ProxyFactoryImpl` (`freeway-ioc`, package-private),
  and `FlowEngineImpl`→`FlowEngineDefault` (`freeway-flow`). `CoercerDefault`
  / `ExchangeMetaDefault` / `JsonCodecDefault` keep their 1.4.0 names.
- **Cloud defaults relocated out of `cloud.internal`** (`freeway-cloud`) —
  the eleven replaceable defaults moved to their feature packages
  (`discovery`/`resilience`/`observe`/`storage`/`secret`/`rpc`), and
  `TransportSecurityDefault` now lives in `rpc/` next to its interface.
  `internal` holds only non-replaceable wiring and carries
  "no stability promise". Imports of `com.jujin.freeway.cloud.internal.*`
  move to the feature packages.
- **`ConfigSpec` moved and renamed** (`freeway-ioc`) — `commons.config` is
  gone; config declarations now use `ioc.symbol.SymbolSpec` (same
  `of(key, type, default, parser)` / `key()` / `parse()` shape).
  `freeway-ext`'s Kafka module is migrated in lockstep.
- **CLASS events are deny-by-default** (`freeway-cloud/events`, security) —
  `PeerHub.receive()` drops CLASS events when `allowed-types` is unset
  instead of accepting any class. TOPIC channels keep their documented
  "empty = allow all" semantics.
- **Mesh sessions enforce hello-first** (`freeway-cloud/events`) — frames
  arriving before the hello/ack handshake are protocol errors (server close
  1002 / client abort); hello is one-shot per session. Older peers that
  skipped the token-gated hello can no longer inject TOPIC events.
- **Mesh reconnect semantics** (`freeway-cloud/events`) — whether to
  re-dial is decided by hub registration, not by who called `close()`;
  sink failures now trigger a re-dial instead of silently losing the peer.

## Changed

- **Naming rules written down** — CLAUDE.md / AGENTS.md / DEVELOPER-GUIDE
  state the substitutability criterion, the orthogonal package-location rule
  (`internal` is part of Freeway; a `XDefault` may live there when
  substitution never names it — `PoolDefault` precedent), and the engine
  "public = cross-subpackage contract, not API" convention. Docs drift from
  the audits is fixed (dialect override path, `@Primary` equivalence,
  module dependency map, `ExprEvaluator` size, flow `GraphSpec2` history).
- **`SymbolSource.resolve(SymbolSpec<T>)` default method** (`freeway-ioc`) —
  replaces the 32 call sites of the two-step
  `spec.parse(symbols.resolve(spec.key(), null))` idiom; fully
  backward-compatible.
- **Engine internals narrowed** (`freeway-http`) — 21 engine types with no
  out-of-package references became package-private (frame classes,
  `SessionBuffered*`, WS protocol types); the static WS read-loop utility
  `WebSocket` → `WebSocketReadLoop`. Public API is unaffected.
- **Internal packages speak for themselves** — package-info files on every
  `internal` package (ioc/boot/http/db/cloud/flow) and `commons.util`
  declare the no-stability-promise contract; flow's engine pieces
  (`Stepper`, `FlowContextImpl`) moved into `flow.internal`;
  `commons.logging`'s JUL provider internals became package-private;
  `Container`'s class javadoc was orphaned above the imports and is fixed.
- **DB ergonomics** (`freeway-db`) — `DatabaseHub.of(Map)` root factory
  replaces the javadoc nudge to construct the internal implementation; new
  `DialectSyntaxTest` pins the four dialects' pure syntax surface.
- **Single-source cloud config** (`freeway-cloud`) — seven config keys'
  defaults live once in `CloudConfigKeys.*_DEFAULT`, shared with the
  library fallbacks; `CloudHttpClientDefault.Wiring` (record + 7 withers)
  is the one customization seam and `ResiliencePolicy` became a
  package-private helper beside it.

## Fixed

- **RPC multi-mapping** (`freeway-cloud`) — a process can export several
  RPC mappings; `/rpc/<mapping>/{method}` encodes the mapping and invalid
  names fail at export time instead of silently shadowing siblings.
- **RPC reject details no longer become 500s** — the
  `X-RPC-Reject-Reason` header is form-encoded; 4xx contracts survive
  hostile path segments.
- **Mesh split text frames** — `TextMessageAssembler` reassembles
  CONTINUATION frames (4–16 MiB events no longer drop), bounded by the
  same 16 MiB cap as the server side.
- **`callAsync`/`close()` registration race** — futures are registered
  under the close monitor before submission.
- **Registry hook stop no longer masks the real failure** — the registry
  lookup is best-effort during teardown.
- **Retry policy NPE without the resilience module** — the dead
  field is gone; a missing retryer falls back to `RetryerDefault`.
- **HTTP/2 stray frames after RST** — reset streams never receive response
  frames (RFC 7540 §8.1); `sendReset()` is idempotent.
- **`SslContextFactory` empty keystore password NPE** — `null` passwords
  pass through to `KeyStore.load`.
- **TLS reload follows the active engine** — `SslReloader` only wires when
  the built-in engine is the active binding.
- **`/metrics` three-role binding** — one `MetricsDefault` instance is
  bound as concrete class, `Metrics` primary, and `MetricsSnapshot`.
- **Mesh sanitization & identity fallback** — peer exception headers are
  decoded/sanitized once; mesh identity resolves through the same
  registry → `freeway.app.name` → freeway-app chain as HTTP identity.
- **Cloud P3 batch** — `/health/ready` contributor timeout budget, tracer
  thread-affine span stacks, per-service breaker/limiter shard cap,
  configurable peer handshake/backoff keys.

## Notes

- Audit reports: `AUDIT-PACKAGE-STRUCTURE-2026-09-05.md`,
  `AUDIT-CLOUD-STRUCTURE-2026-09-04.md` (and the dated cloud audit trail)
  document the structure and cloud passes; each carries a decision/execution
  log.
- freeway-ext 1.5.0 is aligned (parent/`freeway.version`, `SymbolSpec`
  migration, `CloudHttpClientDefault`/`Wiring`, `HttpContextImpl` rename in
  the benchmark module) and builds against core 1.5.0.
