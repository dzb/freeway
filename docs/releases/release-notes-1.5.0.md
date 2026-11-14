# Freeway 1.5.0 Release Notes

> Version: 1.5.0 ｜ Highlight: **the naming rule is settled around outside substitutability — every replaceable default now lives beside its interface, and module internals draw an explicit "no stability promise" line**

## Headline: settlement, not features

1.5.0 is the release where Freeway names its own conventions out loud. Two
audit passes (package structure, cloud) ran against the codebase, and the
results were codified instead of left as lore:

- **`XDefault` vs `XImpl` is one question** — can the outside replace the
  role (`.primary()` binding, constructor selection, config activation)?
  The framework's implementation of a replaceable role is `XDefault`; the
  rest is `XImpl` (assembly pieces, engine-internal components, per-owner
  artifacts). Package location is orthogonal: `internal` means "no
  stability promise", not "hidden", and an `XDefault` may live there when
  substitution never names it (`PoolDefault` in `db/internal`).
- **All twelve cloud defaults sit beside their interfaces** — the
  replaceable defaults moved out of `cloud.internal` into their feature
  packages (`discovery`/`resilience`/`observe`/`storage`/`secret`/`rpc`),
  including `TransportSecurityDefault` in `rpc/`. `internal` now holds only
  non-replaceable wiring.
- **Internals declare their boundary** — every `internal` package and
  `commons.util` gained a package-info stating the no-stability-promise
  contract; the HTTP engine's public types that existed only because Java
  has no sub-package visibility were narrowed to package-private
  (`WebSocket` → `WebSocketReadLoop`, 21 types demoted, no API change); flow
  engine pieces moved to `flow.internal`; the JUL provider internals are no
  longer public.

The net renames relative to 1.4.0 are small but breaking: four defaults
became `*Impl` (`HttpContext`, `LoggerSource`, `PooledConnection`,
`ProxyFactory`), `FlowEngineImpl` became `FlowEngineDefault`, and
`commons.config.ConfigSpec` moved to `ioc.symbol` as `SymbolSpec`.
`CoercerDefault` / `ExchangeMetaDefault` keep their names.

## Cloud: the mesh stops trusting its input

The event mesh got the security and correctness pass its real deployment
profile needed:

- **Inbound CLASS events are deny-by-default** — an unconfigured
  allowlist no longer means "accept any class"; TOPIC channels keep their
  documented empty = allow-all semantics.
- **hello-first sessions** — frames arriving before the token-gated
  handshake are protocol errors, and reconnect decisions follow hub
  registration rather than whoever called `close()`.
- The RPC surface was repaired where it lied: multiple mappings per
  process work, reject reasons survive hostile path segments, split text
  frames reassemble within the 16 MiB cap, and reset HTTP/2 streams never
  receive stray frames again.

## Governance

The naming criterion, the `internal` semantics, the engine "public =
cross-subpackage contract" convention and the dialect/config/dependency
facts are now written in `CLAUDE.md` / `AGENTS.md` / the developer guide,
with the changelog recording the decisions.

## Modules

- **freeway-commons** — coercion/json defaults keep their names; JUL
  provider internals package-private; `util` boundary documented.
- **freeway-ioc** — `SymbolSource.resolve(SymbolSpec)` default method
  removes the two-step parse idiom; naming settlement (`LoggerSourceImpl`,
  `ProxyFactoryImpl`).
- **freeway-http** — engine internals narrowed (visibility + names); no
  public API change.
- **freeway-flow** — engine machinery into `flow.internal`; package-info
  stability contract.
- **freeway-db** — `DatabaseHub.of(Map)` root factory; direct dialect
  syntax tests.
- **freeway-cloud** — defaults relocation, mesh hardening, `Wiring`
  records and single-source config keys, three-role `/metrics` binding.
- **freeway-ext** — released in lockstep at 1.5.0 against the settled
  names (`SymbolSpec`, `CloudHttpClientDefault`/`Wiring`,
  `HttpContextImpl`), verified by a full offline build.
