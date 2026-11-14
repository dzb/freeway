# Freeway / Solon / Spring Design Comparison

> Setting aside "are the features complete", "is the ecosystem big", "are there many users" — this is only about the design itself.
>
> This document answers "**how well is it designed**"; it is an evergreen design commentary. For the engineering-dimension selection assessment (including Quarkus/Micronaut,
> engine and performance, Agent friendliness), see [framework comparison](framework-comparison.md).

---

## 1. Comparing the Number of Core Abstractions

The number of core abstractions in a framework is a direct measure of the designer's restraint. A core abstraction means: **a type you must understand before you can understand the framework**.

### Spring

```
BeanFactory → BeanDefinition → BeanPostProcessor → ApplicationContext
→ Environment → PropertySource → ResourceLoader → MessageSource
→ ApplicationEvent → ApplicationListener → TransactionManager
→ ProxyFactory → Pointcut → Advisor → Joinpoint
```

Roughly: **14-16 core abstractions**, in a **vertically stacked** structure — each layer consumes the one below it.

### Solon

```
SolonApp → AppContext → Plugin → EventBus → SolonProps → Router → MethodWrap
```

Roughly: **7 core abstractions**, with a flatter hierarchy — `AppContext` is both the IoC container and the application context, with no layering, so each abstraction has higher functional density.

### Freeway

```
Container → Binder → ModuleEx → Defer → Extension<T> → RuntimeHook
```

Roughly: **6 core abstractions**, with **no hierarchical dependencies** — Container does not depend on Defer, Defer does not depend on ModuleEx; completely flat.

---

## 2. The "Subtraction Mindset" of the Design Language

A key indicator of design aesthetics: **whether the designer has the courage to delete code they have written**.

### Spring: addition, occasional refactoring

It tends to "add a replacement rather than delete the old one". The API surface shows many traces of "old and new coexisting":

- XML configuration + annotation configuration + Java Config coexisting for a long time
- The `&` prefix of `FactoryBean` has never been marked as deprecated
- The interface bloat of `ApplicationContext` has never been cleaned up
- `@Bean` in `@Configuration` classes and `@Bean` in `@Component` classes behave differently, but both are kept

This is not a design flaw — it is an engineering choice where **backward compatibility outranks design consistency**.

### Solon: cautious addition

The core abstractions are relatively stable, without Spring's historical burden (no XML era), so the API surface is cleaner. `AppContext` has carried multiple responsibilities since its inception, and this design has never been challenged; `EventBus` has always been a static class and has not been refactored into an interface.

It can be summarized as "**done right from the start, so nothing needs deleting**" — but more likely it has not yet gone through 20 years of production pressure testing like Spring.

### Freeway: subtraction as the first principle

This is the most striking **cultural difference** between Freeway and the other two. Several versions in the CHANGELOG have a "Removed" section. Deleted items include:

- `inject()` — because it encouraged the anti-pattern `new X() + container.inject(x)`
- `Extension.Key` — because "it was unused; `Class<?>` is enough"
- `@Named` — replaced by `@Inject("id")`
- `Module2` (renamed to `ModuleEx`) — because it conflicted with `java.lang.Module`
- Strict mode — because `System.setProperty` served as a side-effect channel
- `logging.properties` — replaced by `freeway-logging.properties`
- Extension adapter module, benchmark module — moved to an external repository
- `Route.handlerType` field — because the 4-field version's constraint invariants were scattered across multiple consumers

Route going from 4 fields to 3 is a typical case: spot a design flaw → don't patch it → re-examine the abstraction → introduce the `ResolvableHandler` wrapper → delete the redundant field. **This is not "fixing a bug", it is design iteration**.

The repository conventions in `AGENTS.md` state this as a rule:

> **No compatibility shims**: a renamed key, a replaced API shape or a superseded
> mechanism is deleted, not kept alongside its replacement.

**Verdict**: on the dimension of "courage to delete", Freeway is far above Solon, and Solon far above Spring. This makes Freeway's API surface look newer than the age of its code.

---

## 3. Explicitness vs Implicitness

### Spring: kingdom of the implicit

"Convention over configuration" also brings the biggest design cost — **loss of local reasoning about implicit behavior**:

```java
@Service
public class OrderService {
    @Autowired PaymentService paymentService;
}
```

You cannot understand from this class when, how, or from where `PaymentService` is injected. You need the full classpath-scanning context to reason about this class's behavior. Another example:

```java
@SpringBootApplication
public class App { }
```

Behind this one line, the conditions of hundreds of auto-configuration classes must be evaluated. The `/actuator/conditions` endpoint is needed for "post-mortem forensics". **The failure mode has nothing to do with intuition**.

### Solon: controlled implicitness

Annotation-driven, so there is implicit behavior too, but **the scope is more controllable**:

- The classpath scanning scope is clearer (SolonApp's package-scanning convention)
- Plugins are registered through `META-INF/solon/*.properties`; the path is centralized and the rules are simple
- The auto-configuration mechanism is less complex than Spring Boot's; the scope of condition evaluation is limited
- Lifecycle event ordering is predefined; subscribers fire in a predictable order

It does not fully solve the "loss of local reasoning" problem (`@Inject` still does not show its injection source at the call site), but implicit behavior's **coverage** and **nesting depth** are both lower than Spring's.

### Freeway: all relationships live in the binder

The design language is explicit by nature:

```java
FreewayApp.create(new HttpModule(), new DbModule(), new MyModule())
    .start();
```

- All modules are listed here → you know which components exist
- All bindings are declared in `Module.bind(Binder)` → if you see it, it exists; if you don't, it doesn't
- All extensions are contributed through `binder.contribute(Class).add(...)` → the contribution point and its ordering rules sit together
- All configuration is loaded through the configuration cascade → the priority of environment variables, properties files and CLI arguments is known

The only exception is the disclosed SPI: `FreewayApp.run(...)` by default also loads modules declared in `META-INF/services`
(disabled by `autoDiscovery(false)`). Binding still never scans.

You can **understand the structure of the entire application by reading one file**. The ability to "reason locally" is restored.

The cost: you have to write that file. **This is exactly the price of explicitness**.

**Verdict**: the three frameworks sit at three different points on the "explicit-implicit" spectrum. Freeway's choice (fully explicit) is extremely rare among modern frameworks — almost all frameworks have chosen the annotation-driven implicit path. Freeway went the other way.

---

## 4. Differences in Module Boundary Design

### Spring: module boundaries are package names

```java
org.springframework.beans
org.springframework.context
org.springframework.transaction
org.springframework.aop
```

Module boundaries are **namespace-level**, with **no enforced boundary** — `BeanPostProcessor` and `ApplicationContext` can reference each other even though they belong to different layers semantically. Spring Boot's auto-configuration ordering is established through `@AutoConfigureAfter`/`@AutoConfigureBefore` annotations — runtime "soft constraints" with no compile-time guarantee.

### Solon: module boundaries are Plugin

```java
public interface Plugin {
    void start(AppContext context);
    void preStop();
    void stop();
}
```

The module boundary is an **explicit contract** — the `Plugin` interface. `Plugin.start()` receives `AppContext` but not `SolonApp`; a plugin cannot control the application's overall lifecycle, only operate the container. This is a **limited-power** design decision.

### Freeway: module boundaries are architectural dividing lines

The module boundary is more than the `ModuleEx` interface; it is a **cross-module layering convention**:

```
freeway-http/
└── src/main/java/com/jujin/freeway/http/
    ├── route/Route.java        ← record, zero container dependency
    ├── route/RouteHandler.java ← @FunctionalInterface, zero container dependency
    ├── route/RouteIndex.java   ← pure trie data structure, zero container dependency
    ├── HttpPipeline.java       ← record, handler declaration (HttpServer.create is the sole derivation)
    └── HttpModule.java         ← the only container-aware point (same module, different responsibility — conceptual boundaries are not artifact boundaries)
```

`RouteIndex` does not know about `Container`; `DatabaseImpl` does not know about `Container`. IoC belongs to the Module, not to the core implementation. This is an **architectural layering strategy — not an interface convention but a design culture**.

This means:

- core code can be tested independently (no container needed)
- core code can be reused by other frameworks (no framework lock-in)
- Module is the only entry point with framework awareness
- if you don't use IoC, you can assemble directly with `HttpServer.create(engine, config, pipeline)` with zero container, sharing the same derivation point as the container path

**Verdict**: Spring's module boundary is the namespace (weak), Solon's is the Plugin interface (medium), and Freeway's is a **cross-module layering contract** (strong).

---

## 5. Use of Modern Java Language Features

This is where Freeway opens a generational gap with the other two.

### Spring: language conservatism

The core abstractions date from the Java 1.3-1.5 era. Even today:

- The `BeanPostProcessor` signature is still `Object postProcessBeforeInitialization(Object bean, String beanName)` — not generic
- Configuration attributes still use string literals (`"classpath:..."`, `"${...}"`)
- The startup flow is rigidly orchestrated in one `refresh()` template method — a dozen-plus lifecycle steps with hardcoded order
- The core runtime abstractions do not make full use of JDK 17's sealed classes, pattern matching, and records

The design language is **frozen in the Java 5-8 era**.

### Solon: pragmatic, moderate modernization

It makes full use of annotations, functional interfaces and lambdas. The API style is in sync with JDK 8:

```java
Solon.start(MyApp.class, args, app -> {
    app.get("/hello", ctx -> ctx.text("Hello"));
});
```

But it does not use records, sealed classes or ScopedValue. Its design language is **modernized at the JDK 8-11 level**.

### Freeway: JDK 25 as the design foundation

Every core abstraction uses Java 25 capabilities:

- **ScopedValue**: the foundation for Defer, transaction scope and thread scope — replacing ThreadLocal
- **Record**: `Route`, `RouteGroup` and `HttpPipeline` are all records — declaration is definition
- **@FunctionalInterface**: `RouteHandler`, `HttpFilter` and `ModuleEx` are all functional interfaces
- **Pattern matching / virtual threads**: `instanceof` binding and switch arrow dispatch are standard (230+ places across the repository),
  one virtual thread per connection carries the HTTP engine. `sealed class` is not used for type hierarchies — modernization is selective,
  not feature stacking

ScopedValue fundamentally changes a programming model:

- ThreadLocal's leak risk → zero leaks (ScopedValue cannot escape)
- ThreadLocal's inheritance problem → zero problems (ScopedValue is automatically inherited by child threads)
- ThreadLocal's manual cleanup → zero need (released automatically when the scope ends)

Transaction scope, Defer delayed execution, and request-level Scope come "free" — no ThreadLocal cleanup logic, no InheritableThreadLocal propagation conventions, no manual remove in finally.

**Verdict**: Spring's design language settled in Java 5-8, Solon's in JDK 8-11, and Freeway's design language co-evolves with JDK 25. Spring chose to be usable by 20 million developers; Freeway chose to "evolve with the latest JDK and accept no historical baggage".

---

## 6. Transparency of Design Decisions (the Framework's "Honesty")

Whether the framework openly admits trade-offs, or packages itself as "completely correct".

### Spring: trade-offs rarely documented

The official reference documentation runs 1000+ pages, but most of it is API reference and configuration description, and it rarely discusses **design trade-offs**. For example, on the issue of `@Bean` methods behaving differently in `@Configuration` and `@Component`: the documentation only tells you "you should declare `@Bean` methods in `@Configuration` classes", but does not discuss **why the two behave differently** (CGLIB proxy vs no proxy) or **why this difference is kept** (backward compatibility vs design consistency).

### Solon: a measured amount of design explanation

The DeepWiki documentation covers architecture explanation and API reference, but "Design Decision" content is scattered, with no centralized record of trade-offs.

### Freeway: the why of a decision travels with the change

Freeway has no centralized "design decisions" document — the why is written in the change records themselves:

- **CHANGELOG's Removed / migration entries carry their own deletion rationale**: `CorsFilter.Builder` was deleted because
  "an object that restates policy defaults is a second holder of the same answer"; `internal.HttpModuleConfig` was deleted
  because it was "a second owner of keys — restating the defaults of 13 server knobs one by one";
- **Complex modules have their own decision documents**: `docs/design/flow.md` records them one by one
  (e.g. "Striped Lock for Singleton Concurrency": `ConcurrentHashMap.computeIfAbsent`
  throws "Recursive update" in recursive scenarios, so a reentrant lock was chosen);
- **Release notes carry the trade-offs of the moment**: `docs/releases/release-notes-*.md` records, version by version, the background of each introduction and trade-off
  (e.g. 1.5.0 discusses the division of labor between `.primary()` bindings, constructor selection and config activation).

Each decision corresponds to a **real problem scenario** (not an abstract possibility), followed by a **concrete trade-off analysis**.

**Verdict**: this is not a difference in documentation volume but in **design culture**. Freeway treats "recording trade-offs" as part of design rather than after-the-fact documentation work. This reflects respect for framework users — not treating them as people who "only need to know how to use it".

---

## 7. Overall Comparison

| Dimension | Spring | Solon | Freeway |
|---|---|---|---|
| **Core abstraction count** | 14-16, hierarchically stacked | 7-8, high functional density | 6, completely flat |
| **Subtraction mindset** | Weak (heavy historical debt) | Medium (stable, but few deletions) | Strong (deletion is culture) |
| **Explicitness** | Mostly implicit | Moderately implicit | Fully explicit |
| **Module boundary strength** | Weak (package = module) | Medium (Plugin convention) | Strong (cross-module layering contract) |
| **Design language era** | Java 5-8 | JDK 8-11 | JDK 25 |
| **Trade-off transparency** | Low | Medium | High |
| **Coupling of core code to the framework** | High (Spring everywhere) | Medium | Low (core has zero container dependency) |

---

## 8. Each Framework's Distinct Value Proposition

### Spring
- **Value**: convention over configuration, extremely low decision cost
- **Cost**: you must trust its conventions and understand the complex adaptation layer behind them
- **Good fit**: large-scale teams, rapid delivery, ecosystem-rich scenarios

### Solon
- **Value**: lighter than Spring, while retaining the annotation-driven developer experience
- **Cost**: less ecosystem than Spring, but sufficient
- **Good fit**: teams that need something lighter than Spring but don't want to change paradigms

### Freeway
- **Value**: fully transparent, zero magic, you fully control every binding relationship
- **Cost**: you must write all relationships explicitly (this is both a cost and a value)
- **Good fit**: needs full runtime control, maximum performance, small teams or personal projects

---

## 9. Conclusion

The three frameworks serve different **trust models**:

- **Spring** asks you to trust its auto-wiring
- **Solon** asks you to trust its lightweight annotations
- **Freeway** does not require you to trust anything — every line of code is right there; you can read it all, understand it, and modify it

In terms of purity and consistency of design language, **Freeway ranks above Solon, and Solon above Spring**. But purity of design language has never been the primary criterion for choosing a framework — it is an extra reward for those who can appreciate it.
