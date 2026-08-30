# Freeway 1.3.11 Release Notes

> Version: 1.3.11 ｜ Highlight: **audit-driven hardening — one reflective-dispatch idiom, honest failure surfaces, key-named config errors**

1.3.11 is an audit release: a full pass over freeway-cloud and the IoC
message domain closed every confirmed defect, unified the remaining
reflective-dispatch hot path with the framework's MethodHandle idiom, and
made configuration and registry failures name themselves. ~1,220 tests
green across all core modules.

## Added

- **`MethodHandleUtils.defaultMethodHandle`** (`freeway-commons.bean`) —
  cached non-virtual (`findSpecial`) handle for invoking interface default
  methods on proxy receivers; the virtual handle from `methodHandle` would
  re-enter the proxy instead of running the default body. Same
  ClassValue-keyed lock-free caching as the existing handle family.
- **`ConfigValues`** (`freeway-ioc.symbol`) — typed bind-time config
  parsing (`intValue`/`longValue`/`doubleValue`): a malformed value fails
  fast naming the key and the rejected raw value
  (`freeway.cloud.rpc.connect-timeout must be an integer: 'soon'`)
  instead of a bare `NumberFormatException`. Lives beside the
  `SymbolSource` it consumes, so every config-resolving module can use it;
  the four freeway-cloud modules and freeway-ext `freeway-mq-kafka` are
  wired.

## Changed

- **CallBus dispatch rides cached method handles** (`freeway-ioc`) — the
  hot path switched from raw `Method.invoke` to registration-resolved
  `MethodHandleUtils.invokeOn`, matching the AOP/Lifecycle idiom. Business
  exceptions arrive unwrapped (no `InvocationTargetException` artifact);
  DeadCall default-method degradation uses the shared cached handle. One
  observable difference: a direct `call(topic, payload)` with mismatched
  arity/types now fails with `WrongMethodTypeException`/`ClassCastException`
  instead of `IllegalArgumentException` (no contract depended on it).

## Fixed

- **EventBus / CallBus observe the primary Metrics** (`freeway-ioc`) — both
  buses were constructed eagerly in the container constructor and froze the
  pre-load `NoopMetrics` builtin, so a module-supplied primary registry
  (e.g. `CloudObserveModule`) never received the `eventbus.*`/`callbus.*`
  counters. They now realize lazily on first resolution — always after every
  module has bound. Shutdown semantics unchanged.
- **freeway-cloud audit batch** — `ObjectStorageDefault`: `delete` emits
  `ObjectDeletedEvent` only when an object was actually removed; `put`
  writes through a temp file + atomic replace (no symlink TOCTOU window);
  `list` skips out-of-root symlinks. `BaggagePropagator` percent-encodes
  keys/values — separators, spaces and non-ASCII round-trip instead of
  corrupting the wire. `CloudHttpClientDefault`: unmapped local failures
  surface as non-retryable `CloudException.dispatch` (a half-open probe
  always settles); rate-limit/circuit-open rejections count in
  `cloud.rpc.failures`. `Endpoint` validates URI renderability at
  construction and normalizes the base path. `PeerConnector` parses IPv6
  peer addresses and aborts handshakes that never complete (10s watchdog).
  `CloudConfigDefault` delivers change notifications on a dedicated
  executor in change order — `reload()` never blocks on listeners.
  `ReadyHandler` fails on duplicate contributor names at construction;
  `CloudObserveModule` names the offending implementation when a custom
  `Metrics` cannot export `/metrics`; registering a bind-all host warns at
  startup.

## Docs

- Design baselines re-audited against the implementation: cloud unified
  design (orchestration order, propagator signatures, storage safety,
  events config keys), remote-CallBus wire contract (exception shapes,
  `RpcEndpoint` API, dropped never-implemented config keys), events design
  (PeerHub naming, rejection close codes, dedup mechanism), plus module
  graphs in README / DEVELOPER-GUIDE now include freeway-cloud.
