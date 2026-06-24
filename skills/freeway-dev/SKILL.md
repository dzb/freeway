---
name: freeway-dev
description: 基于 Freeway 框架构建 Java 应用。当用户提到 Freeway、module、Module2、FreewayApp、binder.install、IoC 容器、DbModule、HttpModule、AppBuilder、路由、ORM、EventBus、Defer、ScopedCache、HealthCheck、HealthFilter、PooledConnection、PostgresDialect、SchemaEntity、freeway-ext 等框架相关术语时触发。涵盖模块编写、依赖注入、HTTP API、数据库操作、事务、事件总线、类型转换、延迟执行、验证、连接池、数据库方言、Schema 迁移等所有方面。同时也适用于回答 Freeway API 用法、项目结构、最佳实践和代码生成类问题。
---

# Freeway Development Skill

Freeway is a JDK 25+ Java framework built around compose-first modules, explicit wiring, and minimal ceremony.

## Use This Skill For

- `Freeway`, `module`, `FreewayApp`, `Module2`, `Binder`, `Binding`, `Container`
- `HttpModule`, `WebServerBuilder`, `Route`, `HttpFilter`, `HealthCheck`
- `DbModule`, `DatabaseBuilder`, `Orm`, `Schema`, `MigrationRunner`
- `EventBus`, `RuntimeHook`, `Defer`, `ScopedCache`, `Scoping`
- API usage, module layout, and code generation for Freeway projects

## Working Rules

- Trust the repository code over this skill when they disagree.
- Prefer canonical, compileable examples over broad prose.
- Keep edits scoped. Do not introduce new dependencies unless the codebase already uses them and the change is justified.
- Use `references/` for module-specific details and edge cases.
- If a snippet is meant to be copied, it should compile as written or be clearly marked as pseudo-code.

## Core Shape

- `freeway-commons` - shared JSON, coercion, scoped primitives (`Defer` for commit-time deferral, `ScopedCache` for scope-lifetime caching), bean, validation, logging
- `freeway-ioc` - container, binding DSL, scopes, injection, contributions, AOP, event bus
- `freeway-boot` - app bootstrap, config cascade, profiles, runtime lifecycle
- `freeway-http` - routing, filters, static resources, multipart, SSE, WebSocket
- `freeway-db` - JDBC access, transactions, pools, migrations, schema
- external adapters live in `freeway-ext`

## Canonical Entrypoints

- `Freeway.create(Module2...)` - container only; modules are the unit of composition
- `FreewayApp.run(String[] args, Module2...)` - full application startup; accepts module instances
- `FreewayApp.of(Module2...)` - builder for advanced startup control; compose modules explicitly
- `DatabaseBuilder` - standalone database construction without IoC
- `WebServerBuilder.builder()` - standalone HTTP server construction without IoC

## Preferred Output Style

- Use concrete class and method names from the repository.
- Keep examples short and task-specific.
- Call out when behavior is an inference rather than a directly verified fact.
- If asked to change docs or code, do the change instead of only describing it.

## References

- [IoC reference](references/ioc.md)
- [Boot reference](references/boot.md)
- [HTTP reference](references/http.md)
- [DB reference](references/db.md)
- [Commons reference](references/commons.md)
- [Gotchas](references/gotchas.md)
