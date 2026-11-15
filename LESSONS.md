# LESSONS — Freeway refactor lessons

> This file records what actually went wrong during the 2.0 boundary refactor,
> why it went wrong, and the rule that prevents the same class of mistake.
> It is not a blame log; a lesson that cannot change a future decision is noise.

---

## 1. Delete a module only when every consumer moves

**Symptom.** `LocalModule` was deleted from `freeway-fleet`, but tests, docs,
config rows and the demo still referenced it. `ConfigDocsConsistencyTest` failed
with documented keys no module reads; `DocsLayoutConsistencyTest` failed on
links to deleted Fleet pages; the core demos reactor still contained
`demo/registry-server`.

**Cause.** The code move was treated as the unit of work. A module's consumers
are broader: root POM, demos profile, test fixtures, docs, config tables,
`KnownKeys`, CI, samples and release metadata.

**Rule.**

> A module move/delete is complete only after: root reactor, all consumers,
> tests, docs links, config rows, samples and CI agree. Run the docs gates in
> the same change, not after it.

---

## 2. Stale `target/` hides deleted types

**Symptom.** After deleting `LocalModuleLifecycleTest.java`, `mvn test` failed
during JUnit discovery with `NoClassDefFoundError: .../LeaseStore` for the
deleted test class. The source was gone; a compiled class in `target/` was not.

**Cause.** Maven's incremental build kept a class file whose source had been
deleted.

**Rule.**

> After deleting or moving a test/class across modules, run `mvn clean test`
> before believing a green or red result. A stale `target/` is a second source
> tree with no review.

---

## 3. A readiness fixture must flip both `live` and `ready`

**Symptom.** `TestDiscoveryModule` registered a starting instance, called
`markReady(true)`, and discovery still returned nothing. The fixture stored:

    new ServiceHealth(entry.health.live(), ready, now)

`starting()` is `live=false, ready=false`, so the instance stayed not-live
forever.

**Cause.** Readiness was treated as one boolean; the health record has two
independent facts. The production store set `live=true` on a heartbeat, but the
fixture preserved the old bit.

**Rule.**

> A readiness transition sets every bit the state promises: live and ready.
> Test the full transition matrix (`starting → ready`, `ready → not-ready`,
> renew without flipping readiness), not just the final query.

---

## 4. A test fixture must not outrank a configured primary

**Symptom.** Moving registration tests to `freeway-cloud` made
`HealthEndpointsTest` fail at startup: the test app supplied a custom
`ServiceRegistry`, but the fixture had bound its registry with `.primary()`.
Two primaries made the container reject the composition.

**Cause.** The fixture was designed to replace the deleted local backend, not to
participate in a container where the application supplies the real backend.

**Rule.**

> A test fixture binds with its own marker (`@Local`), never `.primary()`.
> Production/test-provided primaries must win. A fixture that steals the role
> is a second production default in disguise.

---

## 5. `KnownKeys` must move with a config namespace

**Symptom.** After Fleet keys changed to `freeway.fleet.*`, `FleetModuleTest`
failed at bind:

    FleetKeys declares [...] outside [freeway.app, freeway.cloud]

**Cause.** The key table moved, but the `KnownKeys` contribution still declared
the old `CloudKeys.PREFIX` namespaces. The key strings and the vocabulary did
not agree.

**Rule.**

> A namespace change is three edits together: literals, `PREFIX`, and
> `KnownKeys.of(...)`. The key table and its declared vocabulary are one fact.

---

## 6. Blanket string replacement corrupts qualified names

**Symptom.** A broad `ConfigKeys.` → `FleetKeys.` replacement turned
`LoadBalancer.ConfigKeys.LOCAL_ZONE` into `LoadBalancer.FleetKeys.LOCAL_ZONE`,
which did not compile.

**Cause.** The replacement matched a suffix, not an owned symbol.

**Rule.**

> Refactor by import/type ownership, never by unqualified text
> replacement. Run `git diff` for collateral changes before compiling; a
> compiler error is lucky, a silently changed different owner is not.

---

## 7. A server must not depend on its client for the protocol

**Symptom.** `freeway-fleet-server` depended on `freeway-fleet` only to read
`FleetProtocol` and shared keys. The dependency implied the server needed a
client it never used.

**Cause.** The protocol had no artifact of its own; it lived in the client.

**Rule.**

> When two modules share a contract, make the contract a module. Dependencies
> should point at the shared contract, not at another implementation.

---

## 8. Ext importing `cloud.internal` is a missing public contract

**Symptom.** Ext classes imported `CloudHttpClients`, `ConstantTime` and
`OptionalBinding` from `cloud.internal`.

**Cause.** The helpers were useful outside cloud, but no public owner existed.

**Rule.**

> An out-of-module consumer either promotes the smallest public contract or
> proves the helper belongs lower. Copying a small internal helper hides the
> ownership problem; widening `internal` makes it permanent.

---

## 9. Move the demo with the module

**Symptom.** `demo/registry-server` stayed in core after Fleet Server moved to
ext; the core demos profile no longer built.

**Cause.** The demo was treated as documentation rather than a consumer.

**Rule.**

> Demos, samples and benchmarks are consumers. Move or update them in the same
> change as the module they consume; otherwise CI's demo gate becomes a delayed
> migration alarm.

---

## 10. Tests must not inherit a persistent production store

**Symptom.** Making H2 the default Fleet store created a file-backed database
for any `new FleetServerModule()`; tests could share state, leave files behind
or observe each other's leases.

**Cause.** The production default was also the test default.

**Rule.**

> The default may be persistent; tests must inject `MemLeaseStore` (or an
> explicit in-memory H2 URL). A test must not depend on a file in the working
> directory or on the order of previous tests.

---

## 11. Embedded H2 is not HA

**Symptom / risk.** H2 file mode is convenient and persists leases, but
`AUTO_SERVER=TRUE` is a local access mode, not a shared cluster store. Two Fleet
processes pointing at one embedded file do not form a replicated control plane.

**Rule.**

> State the limit next to the default. H2 is the single-node store; HA needs a
> shared PostgreSQL `LeaseStore`. Do not let "it persists" sound like "it
> replicates".

---

## 12. A no-shims rename still needs every consumer in one change

**Symptom / cost.** Fleet moved from `/registry` / `X-Registry-*` /
`freeway.cloud.registry.*` to `/fleet` / `X-Fleet-*` / `freeway.fleet.*`.
Doing half of it would have left a protocol that no reader can name.

**Cause.** The old names were spread across client, server, routes, config,
KnownKeys, tests, demo and docs.

**Rule.**

> No compatibility alias is allowed, so the migration must be atomic across the
> whole family. Compile errors are the migration path; a surviving old name is
> a second contract.

---

## 13. Too many shape changes in one session is a review cost

**Symptom / cost.** The round moved local → ext → delete, renamed `FleetModule`
through multiple forms, split config ownership and moved modules across repos.
Each decision was defensible; together they made review and verification hard.

**Cause.** The target shape was refined while the code was being moved; the
process had no frozen decision list.

**Rule.**

> For a structural round, write the target module/dependency/namespace decision
> first. Then execute in reviewable slices. "No compatibility baggage" permits
> a break; it does not make repeated target changes free.

---

## 14. A deleted backend leaves orphan support code

**Symptom.** `ActiveBindingProbe` remained in `cloud.internal` after local
discovery was removed. It had no producer; only the readiness gate's logging
still referenced it.

**Cause.** Deleting the main class is not deleting its support graph.

**Rule.**

> After deleting a backend, grep its collaborators and remove the now-unused
> seam. Dead support code is harder to spot than a dead main class.

---

## 15. Release provenance is part of the move

**Symptom / risk.** `freeway-fleet` and `freeway-fleet-server` keep the same
Maven coordinates but now publish from `freeway-ext`; the demo is a new ext
module and must not publish.

**Cause.** A repository move changes release ownership, not just source
location.

**Rule.**

> A cross-repository move must update: parent POM, dependencyManagement,
> publishing/skip flags, CI, docs and release order. Consumers see coordinates;
> operators see provenance.

---

## Review checklist

Before declaring a boundary refactor done:

1. Root reactor, demos, tests, docs, config tables and CI agree.
2. `mvn clean test` passes for every affected repository.
3. No new module imports another module's `internal` package.
4. Every shared protocol/config constant has one public owner.
5. `KnownKeys` namespaces match the key literals.
6. Test fixtures are marked, injected, and cannot outrank production bindings.
7. Persistent stores are never the default in tests.
8. Every renamed wire/config namespace has zero surviving old spellings.
9. Deleted modules leave no orphan helper/adaptor behind.
10. Publishing and release order are explicit for moved artifacts.
