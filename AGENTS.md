# Repository Guidelines

## Project Structure & Module Organization

Freeway is a multi-module Maven project targeting JDK 25. The parent `pom.xml` defines dependency versions, compiler settings, and modules. Core code lives under each module's `src/main/java`; tests under `src/test/java`; fixtures and config under `src/test/resources`.

Key modules:
- `freeway-commons`: shared JSON, scalar coercion, validation, and logging utilities.
- `freeway-ioc`: container, binding DSL, scopes, injection, and extension support.
- `freeway-boot`: launcher, runtime lifecycle, profiles, and config loading.
- `freeway-http`: routing with built-in `FreewayHttpEngine` (HTTP/1.1 + HTTP/2 + WebSocket + HTTPS). Undertow engine adapter available in [freeway-ext](https://github.com/dzb/freeway-ext).
- `freeway-db`: JDBC access, transactions, pooling, and SQL migrations.
- Starter artifacts may be listed in dependency management, but they are not part of the current reactor modules.

## Build, Test, and Development Commands

- `mvn test`: compile all modules and run the full JUnit suite.
- `mvn -pl freeway-ioc test`: test one module only.
- `mvn -pl freeway-http -am test`: test a module and also build required upstream modules.
- `mvn -pl freeway-db -am test`: run database module tests with dependencies.

Use JDK 25. Preview flags are not currently configured in the parent POM, so do not introduce preview language/API usage unless the build is updated at the same time. Avoid `mvn verify` for routine checks because the parent POM attaches publishing/signing plugins.

## Coding Style & Naming Conventions

Use standard Java formatting already present in the codebase: 4-space indentation, braces on the same line, and concise public APIs. Keep package names under `com.jujin.freeway.<module>`. Prefer constructor injection in examples and tests; field injection is acceptable where the framework behavior is under test. Framework default implementations use the `XDefault` suffix, for example `AppRuntimeDefault` or `JsonCodecDefault`.

## Testing Guidelines

Tests use JUnit Jupiter. Name test classes with the `*Test` suffix and place them beside the module they cover, such as `freeway-commons/src/test/java/.../BeanValidatorTest.java`. Add focused tests for new public behavior, lifecycle rules, coercion, routing, config, database mapping, and regression fixes. Keep test resources module-local.

## Commit & Pull Request Guidelines

Recent history uses prefixes such as `docs:`, `fix:`, `refactor:`, `style:`, and `chore:`. Use short imperative subjects, for example `fix: handle empty service ids`. Pull requests should describe the behavioral change, list affected modules, link issues when available, and include the Maven test command run. Include screenshots only for documentation or externally visible examples.

## Agent-Specific Instructions

Keep edits scoped to the requested module and preserve explicit composition over scanning or hidden discovery. Do not add external dependencies to core modules unless the module already depends on that library and the change is justified.

When touching resource or lifecycle boundaries, add regression coverage for the failure mode:
- Static file serving must keep resolved real paths inside the mount root and must not allow symlink traversal.
- Database transactions and streams must release pooled connections exactly once, including exception paths and repeated `close()` calls.
- Runtime hook resolution should fail startup on invalid hook configuration instead of silently skipping hooks.
- SQL parameter parsing must respect strings, comments, PostgreSQL `::` casts, and repeated named parameters.
