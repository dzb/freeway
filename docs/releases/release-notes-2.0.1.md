# Freeway 2.0.1 Release Notes (unreleased)

> Version: 2.0.1 (unreleased) | Highlight: **Short `f2` / `F2` configuration aliases, config-driven static discovery, and one owner for membership, configuration and cloud package boundaries.**

2.0.1 is the post-2.0.0 consolidation release. It keeps the canonical
`freeway.*` configuration namespace and removes duplicate internal owners; the
Java API and member vocabulary are intentionally not aliased. No 2.0.1 artifact
is published yet — Maven Central's latest is 2.0.0 — so this page describes the
release as planned, not as shipped.

## Highlights

### `f2` / `F2` are input aliases

The canonical framework namespace remains `freeway.*`. Both `f2.*` and `F2.*`
are normalized at the configuration boundary before profile selection, source
merging, symbol lookup and unknown-key checking:

```text
f2.http.server.port  ->  freeway.http.server.port
F2.http.server.port  ->  freeway.http.server.port
```

The environment spellings are `F2_*` / `f2_*`. The alias is accepted in
properties and JSON files, CLI arguments, JVM system properties and mapped
environment variables. An alias/canonical collision in one source is a
duplicate-key error; different source tiers retain normal precedence. Modules
and `ConfigKeys` only ever see `freeway.*`; the alias never becomes a second
module vocabulary.

### Static discovery can be configured

`StaticModule` can be placed without constructor arguments and reads
`freeway.cloud.discovery.static.services` as endpoint-only entries:

```properties
freeway.cloud.discovery.static.services=\
  orders=https://orders-1:8443,\
  orders=https://orders-2:8443
```

Static discovery has no registry, heartbeat, readiness, eviction or hot reload.
Use `StaticModule.of(...)` when instance metadata is required. The
configuration form deliberately carries no weight, zone or canary metadata.

### Membership has one backend-neutral vocabulary

`freeway-cloud` now owns the membership lifecycle in `cloud.member`:
`MemberModule`, `MemberHook`, `MemberState`, `MemberReadiness`, `NodeIdentity`,
`HttpServiceDeclaration` and `BackendGuardHook`.

The member identity and lifecycle keys are:

```text
freeway.cloud.member.service-id
freeway.cloud.member.service-host
freeway.cloud.member.service-scheme
freeway.cloud.member.service-port
freeway.cloud.member.service-instance-id
freeway.cloud.member.shutdown-drain
```

`freeway.cloud.registry.type` remains the registry-backend declaration. The old
`freeway.cloud.registry.service-*` and
`freeway.cloud.registry.shutdown-drain` keys are removed without aliases. Fleet
and Consul adapters continue to supply transport, not lifecycle.

`BackendTypeGuard` no longer has a special `local` type: a non-blank
`freeway.cloud.{registry,discovery,secret,storage}.type` declaration is checked
against the bindings that are actually present.

### Cloud package boundaries are explicit

Event wire/transport helpers moved to `cloud.event.internal`; RPC implementation
machinery moved to `cloud.rpc.internal`; the empty
`cloud.discovery.internal` package was removed. `cloud.http.HttpClients` remains
the public shared outbound-client helper. The public and internal-package
surfaces are pinned by `api/cloud-surface.txt` and
`api/internal-surface.txt`.

### Configuration and IoC internals have one owner

`ConfigCascade.Sources` owns the loaded source maps, `ConfigFilePlan.Reader`
owns file parsing, and `ServiceKey.normalize` owns service-id normalization.
The retired `ConfigSources`, `ConfigMaps`, `ConfigFileReader` and `ServiceIds`
helpers are gone; module-facing configuration and binding behavior is unchanged.

## Verification (2.0.1-SNAPSHOT)

```text
mvn -o test
  commons 450 · ioc 344 · boot 141 · http 550
  db 550 · flow 147 · cloud 382
  total 2,564 — 0 failures, 0 errors, 0 skipped
```

`mvn -o -Pdemos -DskipTests compile` compiles the in-repo consumers, and
`freeway-ext` on `2.0.1-SNAPSHOT` builds against the installed core snapshot and
ran 188 tests with 0 failures (7 skipped: the Kafka integration cases need a
broker). This is the state of the snapshot, not of a published release — re-run
both before tagging 2.0.1.

## Compatibility

- Core artifacts: `2.0.1` (unreleased; the latest published core is `2.0.0`).
- `freeway-ext`: `2.0.1-SNAPSHOT`, built against this core snapshot.
- Java: 25+.
- The member Java types and configuration keys from 2.0.0 are renamed/removed
  without compatibility aliases; migrate through the compiler and the key table
  above.
- The short configuration aliases are input aliases only; `freeway.*` remains
  the one canonical namespace.

The complete change list is in [CHANGELOG.md](../../CHANGELOG.md).
