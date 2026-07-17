## What's Changed

### Added

- **Auto file logging** — `JULEnhancer` now creates a log file by default at `logs/{app.name}.log` (or `logs/freeway.log`). File logging is now the default, no need for `-Dfreeway.log.file`. Opt out with `-Dfreeway.log.file=off`. Uses the time + size dual rolling `JULFileHandler` with GZIP compression.

### Fixed

- **SQL builder PostgreSQL `::` type cast handling** — `SQL.where()`, `.set()`, and `.having()` now correctly handle `::` type casts in fragments with named parameters (e.g. `created_at::date = :d`). Previously the second colon was misinterpreted as a named parameter start, causing "Missing value for named parameter" errors.

### Refactored

- **Logging module audit and polish** — compacted code across all log formatters, replaced FQN with imports throughout, simplified `JULMDCAdapter` with `ThreadLocal.withInitial()` eliminating all null checks, made MDC priority keys configurable via `-Dfreeway.log.mdc.priority`.
- **Redundant FQN cleanup across modules** — removed unnecessary fully-qualified names in `JsonNormalizer`, `ContainerImpl`, `HttpContext`, `FreewayHttpContext`, and `MultipartForm` where imports already covered them.

### Docs

- Update logging section in DEVELOPER-GUIDE.md with auto file logging behavior and configuration options.

**Full Changelog**: https://github.com/dzb/freeway/compare/v1.3.2...v1.3.3
