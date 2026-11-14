# Freeway documentation

Freeway is a JDK 25 framework for server, data, workflow and cloud applications.
This directory is the entry point to the reference documentation; the
repository-level [`README`](../README.md) covers the project itself and
[`AGENTS.md`](../AGENTS.md) holds the contributor conventions.

## Map

| Area | Document |
|---|---|
| Using the framework | [Developer guide](developer-guide.md) |
| Module boundaries, lifecycle, config internals | [Architecture](architecture.md) |
| Every configuration key, by module | [Configuration](configuration.md) |
| The module model (`ModuleEx`, `@SubModule`, composition) | [Module system](module-system.md) |
| Reflection sites and their cache rules | [Reflection](reflection.md) |
| Per-module guides | [Commons](modules/commons.md) · [DB](modules/db.md) · [Cloud](modules/cloud.md) |
| Design decisions and rationale | [Cloud](design/cloud.md) · [Defer & ScopedCache](design/defer.md) · [Flow](design/flow.md) |
| Reference material | [Metrics](reference/metrics.md) · [Failures](reference/failures.md) · [Logging configuration template](reference/logging.properties.reference) |
| Configuration samples | [`samples/`](samples/) |
| Release notes | [`releases/`](releases/) |

## Conventions

- Every documentation directory uses lower-case kebab-case file names
  (`developer-guide.md`, `release-notes-1.5.7.md`); the repository root keeps
  only the conventional upper-case entry points (`README.md`, `AGENTS.md`,
  `CHANGELOG.md`, `TASTE.md`).
- Category directories are plural (`modules/`, `design/`, `samples/`,
  `releases/`, `reference/`); a document lives in the most specific directory
  that describes its role, not in a per-version or per-author folder.
- Documentation is written in English from Freeway 2.0 on.
- Framework comparisons and engineering evaluations are **not** part of this
  normative set; they live under [`demo/reference/`](../demo/reference/) and are
  provided for context only.
