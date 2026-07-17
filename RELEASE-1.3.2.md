## What's Changed

### Added

- **MDC context display in log formatters** — `JULLogFormatterSupport` now renders MDC key-value pairs in log output when MDC context is present. Both `JULConsoleFormatter` and `JULFileFormatter` support MDC rendering.

### Docs

- **Flow module design decisions** — documented graph build path, driver extension points, entry type preservation, cache invalidation, unreachable node serialization, subgraph driver, exception strategy, and `FlowOptions` defensive copy.

### Other

- Style reformat of long lines across `json` and `logging` packages.

**Full Changelog**: https://github.com/dzb/freeway/compare/v1.3.1...v1.3.2
