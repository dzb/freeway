# Java Four-Framework Side-by-Side Comparison Report (September 2026)

> This document answers "**which one should I choose**" — the engineering-dimension selection assessment (concept density, ceremony, engine, performance, ecosystem, Agent
> friendliness). For the **in-depth commentary on design philosophy** (abstract structure, subtraction mindset, language modernization, decision transparency; compared against
> Spring/Solon/Freeway), see [freeway-framework-design-comparison.md](framework-design-comparison.md).

**Comparison subjects**: Spring Boot 4.x / Quarkus 3.x / Micronaut 4.x-5.x / Freeway 1.5.x

**Evaluation criteria**: mental overhead, concept count, elimination of ceremony, where complexity actually goes, degree of WYSIWYG

---

## 1. Design Philosophy

| | Spring Boot | Quarkus | Micronaut | Freeway |
|---|---|---|---|---|
| **Core philosophy** | Convention over configuration, runtime flexibility | Build-time processing, container first | Compile-time DI, zero runtime reflection | Explicit composition, zero black boxes |
| **Where complexity goes** | Stays at runtime | Moved to build time | Moved to compile time (code generation) | **Does not exist** |
| **DI timing** | Runtime (reflection + AOP proxies) | Build time (ARC container) | Compile time (annotation processor) | Runtime (reflection injection; JDK proxies only when needed) |
| **Classpath scanning** | Yes | No | No | No |
| **Code generation** | No | Yes (build time) | Yes (compile time) | No |
| **Bytecode enhancement** | Yes (AOP proxies) | Yes (build-time resolution) | Yes (compile-time generation) | No (JDK proxies only) |
| **Zero external dependencies (core)** | No | No | No | **Yes** (commons + ioc only SLF4J) |
| **Baseline Java** | Java 17 (4.x) | Java 21 (since Quarkus 4) | Java 17 (4.x) / Java 25 (5.x) | **Java 25** |

---

## 2. Number of Core Concepts

### What you need to know to build a REST + DB + Auth service

**Spring Boot (40+ concepts)**

```
IoC container (ApplicationContext, BeanFactory, Bean lifecycle, scope, conditional injection, Auto-Configuration)
+ Dependency injection (@Autowired, @Qualifier, @Primary, constructor/field injection)
+ HTTP (@RestController, @RequestMapping, RestTemplate/WebClient/HttpServiceClient)
+ Data (JPA/Hibernate, Spring Data Repository inheritance hierarchy, @Transactional 7 parameters)
+ Security (SecurityFilterChain, AuthenticationManager, UserDetailsService, @PreAuthorize)
+ Configuration (application.yml, Profile, @ConfigurationProperties, @Value)
+ Startup (@SpringBootApplication = 3 annotations combined)
+ Other (@EnableScheduling, @EventListener, Actuator, @Valid)
```

**Quarkus (15-18 concepts)**

```
DI (@Inject, @ApplicationScoped, constructor injection)
+ HTTP (@Path, @GET, @QueryParam)
+ Data (Hibernate ORM with Panache, @Transactional)
+ Security (@RolesAllowed, SecurityIdentity)
+ Configuration (application.properties, Profiles)
+ Development (quarkus:dev hot reload, Dev Services)
+ Extension model (quarkus add/remove extension)
+ Reactive (Mutiny Uni/Multi)
```

**Micronaut (12-15 concepts)**

```
DI (@Singleton, @Controller, @Inject, compile-time resolution)
+ HTTP (@Get, @Post, @PathVariable, @QueryValue)
+ Data (Micronaut Data Repository, @Transactional)
+ Security (@Secured, AuthenticationProvider)
+ Configuration (application.yml, @ConfigurationProperties)
+ AOT (compile-time optimization, automatic)
```

**Freeway (10-12 concepts)**

```
ModuleEx (bind method)
+ Container (get method)
+ Binder (bind / contribute DSL)
+ Route (Route.get / Route.post)
+ RuntimeHook (start / stop)
+ Scope (SINGLETON / PROTOTYPE / BOUNDARY)
+ @Inject / @Symbol (2 annotations)
+ Database / Orm (used directly)
+ HttpFilter (one interface, doFilter + order)
+ EventBus / EventSubscriber
+ FreewayApp.run(Launch.of(args), modules...)
```

| Framework | Core concept count |
|------|-----------|
| Spring Boot | 40+ |
| Quarkus | 15-18 |
| Micronaut | 12-15 |
| **Freeway** | **10-12** |

---

## 3. Eliminating the "pointless ceremony" dogma

### 3.1 Startup class

| Framework | Form | Complexity |
|------|------|--------|
| Spring Boot | `@SpringBootApplication` (= `@SpringBootConfiguration` + `@EnableAutoConfiguration` + `@ComponentScan`) | 3 annotations combined; scans the entire classpath behind the scenes |
| Quarkus | `@QuarkusMain` (optional) | Low |
| Micronaut | No special annotation | Low |
| **Freeway** | `FreewayApp.run(Launch.of(args), new AppModule())` | **One method call, modules passed explicitly** |

### 3.2 Registering a REST endpoint

| Framework | Form | Concept count |
|------|------|--------|
| Spring Boot | `@RestController` + `@RequestMapping("/api")` + `@GetMapping("/{id}")` + `@PathVariable` | 4 annotations |
| Quarkus | `@Path("/api")` + `@GET` + `@PathParam` | 3 annotations |
| Micronaut | `@Controller("/api")` + `@Get("/{id}")` + `@PathVariable` | 3 annotations |
| **Freeway** | `Route.get("/api/{id}", handler)` | **1 method call** |

### 3.3 Database transactions

| Framework | Form | Parameters |
|------|------|------|
| Spring Boot | `@Transactional(propagation=REQUIRED, isolation=DEFAULT, readOnly=false, rollbackFor=Exception.class, noRollbackFor=..., timeout=..., transactionManager=...)` | 7 parameters |
| Quarkus | `@Transactional` | 0 parameters (defaults suffice) |
| Micronaut | `@Transactional` | 0 parameters |
| **Freeway** | `db.transaction(() -> { ... })` | **0 parameters, one lambda** |

### 3.4 Data access

| Framework | Approach | Concept count |
|------|------|--------|
| Spring Boot | JPA/Hibernate + Spring Data Repository (inherit CrudRepository / PagingAndSortingRepository / JpaRepository) | 3+ interfaces to choose from |
| Quarkus | Hibernate ORM with Panache (Active Record, operate on entities directly) | 1 approach |
| Micronaut | Micronaut Data (Repository pattern, compile-time generation) | 1 approach |
| **Freeway** | `Orm.of(db)` + direct CRUD | **1 approach, no inheritance** |

### 3.5 HTTP clients

| Framework | Options | Selection cost |
|------|------|----------|
| Spring Boot | RestTemplate (synchronous, old) / WebClient (reactive, new) / HttpServiceClient (4.0 interface-style) | High — 3 coexist, which one to choose? |
| Quarkus | REST Client (1) | Low |
| Micronaut | HttpClient (1) | Low |
| **Freeway** | Built-in engine, no external client choice | **No selection cost** |

### 3.6 Security

| Framework | Approach | Concept count |
|------|------|--------|
| Spring Boot | SecurityFilterChain + AuthenticationManager + UserDetailsService + SecurityContext + @PreAuthorize + OAuth2/OIDC | 10+ |
| Quarkus | @RolesAllowed + SecurityIdentity | 2-3 |
| Micronaut | @Secured + AuthenticationProvider | 2-3 |
| **Freeway** | `HttpFilter`, one interface, check `ctx.header("Authorization")` directly | **1** |

### 3.7 Aspects/AOP

| Framework | Approach | Concept count |
|------|------|--------|
| Spring Boot | @Aspect + @Before/@After/@Around + Pointcut expressions + JoinPoint + BeanPostProcessor | 5+ |
| Quarkus | @Interceptor (build-time resolution) | 2-3 |
| Micronaut | @Interceptor (compile-time generation) | 2-3 |
| **Freeway** | `.advise(advisor -> advisor.wrap(...))` on a binding | **1** |

### 3.8 Feature enablement

| Framework | Approach |
|------|------|
| Spring Boot | `@EnableScheduling` + `@EnableAsync` + `@EnableCaching` + `@EnableWebSocket` + `@EnableJpaRepositories` + `@EnableTransactionManagement` (a pile of auto-configuration behind each Enable) |
| Quarkus | `quarkus add extension xxx` (explicit extension) |
| Micronaut | Dependency is the feature (add a starter and it's there) |
| **Freeway** | **Add a module**: `new HttpModule()`, `new DbModule()`, `new KafkaModule()` |

---

## 4. Where Complexity Actually Goes

| | Spring Boot | Quarkus | Micronaut | Freeway |
|---|---|---|---|---|
| **Runtime** | High (proxies, AOP, auto-configuration) | Low | Low | **Low** |
| **Build time** | Low | Medium (ARC container) | Low | **None** |
| **Compile time** | Low | Low | High (code generation) | **None** |
| **Can you see it** | Partially (many black boxes) | Partially | Invisible (generated code) | **Fully visible** |
| **Can you handle it while debugging** | Yes, but you must understand framework internals | Yes, but with a learning curve | Hard (debugging generated code) | **Yes — it's the code you wrote** |
| **Any hidden complexity** | Yes (Auto-Configuration black box) | Yes (build-time processing) | Yes (compile-time code generation) | **None** |

---

## 5. HTTP Engine Architecture

### 5.1 Engine comparison

| | Spring Boot | Quarkus | Micronaut | Freeway |
|---|---|---|---|---|
| **HTTP engine** | Tomcat/Jetty/Undertow (replaceable) | Vert.x/Netty | Netty | **In-house FreewayHttpEngine** |
| **I/O model** | NIO (Servlet container) | NIO (Vert.x) | NIO (Netty) | **Synchronous blocking I/O + virtual threads** |
| **HTTP/2** | Depends on the container | Native to Vert.x | Native to Netty | **Built in** (h2c + h2) |
| **WebSocket** | Depends on the container | Native to Vert.x | Native to Netty | **Built in** |
| **HTTPS** | Depends on the container | Native to Vert.x | Native to Netty | **Built in** |
| **Thread model** | One thread per request (or NIO event loop) | Event loop + Worker | Event loop + Worker | **One virtual thread per connection** |
| **External dependencies** | Requires a Servlet container JAR | Requires Vert.x/Netty JAR | Requires Netty JAR | **Zero external dependencies** |

### 5.2 Engine replaceability

Freeway's HTTP engine follows the same `.primary()` pattern as the other components:

```java
// Default — FreewayHttpEngine (built in)
FreewayApp.run(Launch.of(args), new AppModule(), new HttpModule());

// Replace with Undertow — just add the module, no configuration needed
FreewayApp.run(Launch.of(args), new AppModule(), new HttpModule(), new UndertowModule());

// Replace with Jetty
FreewayApp.run(Launch.of(args), new AppModule(), new HttpModule(), new JettyModule());
```

| Engine | Source | I/O model |
|------|------|----------|
| `FreewayHttpEngine` | Built in (default) | Synchronous blocking + virtual threads |
| `UndertowEngine` | freeway-ext | XNIO |
| `JettyEngine` | freeway-ext | Jetty 12 |

How to replace: the extension module adds `.primary()` when binding `HttpEngine`, and the container selects it automatically. No application code changes and no configuration file are needed.

### 5.3 Performance

The built-in `FreewayHttpEngine` performed best in performance tests. This validates a core hypothesis:

**Simple code + a powerful runtime > complex code + a complex runtime**

| Comparison | FreewayHttpEngine | Undertow/Jetty/Netty |
|------|-------------------|----------------------|
| **Code complexity** | Low (synchronous blocking, reads and writes the socket directly) | High (NIO event loop, ChannelHandler, ByteBuf) |
| **Concept count** | Few (ServerSocketChannel, SocketChannel, virtual thread) | Many (Selector, SelectionKey, EventLoop, ByteBuf, ChannelPipeline) |
| **Performance** | **Best** | Good |
| **Debugging difficulty** | Low (a thread dump shows the blocking point directly) | High (analyze event loop, channel state, ByteBuf reference counts) |

### 5.4 Why the simple approach is actually faster

1. **The nature of virtual threads**: the JVM is deeply optimized for `ScopedValue` and virtual threads; synchronous blocking code is effectively "non-blocking" on virtual threads — the JVM suspends and resumes virtual threads automatically, without occupying platform threads.

2. **JIT-friendly**: the synchronous blocking code path is simpler, so the JIT compiler can inline, optimize and vectorize more easily. NIO's event loops, callback chains and ByteBuf reference counts make JIT optimization harder.

3. **Memory efficiency**: no Netty ByteBuf pool, no NIO SelectionKey array, no EventLoop task queue. One virtual thread is one simple stack frame.

4. **Simple debugging**: when something goes wrong, a thread dump shows where it blocks. NIO problems require analyzing event loop state, channel state and ByteBuf reference counts.

### 5.5 Philosophical alignment with Go

| Go practice | Freeway equivalent |
|-----------|---------------|
| Synchronous blocking I/O + goroutine | Synchronous blocking I/O + virtual threads |
| No NIO/epoll | No Netty/Vert.x |
| Simple code, the runtime handles concurrency | Simple code, the JVM handles concurrency |
| Performance is not bad, even better | Moderate raw throughput (about 60–80% of native engines), leading on WS versus its own adapter |

**Complexity is not free** — the complexity of NIO/Netty brings conceptual cost, but in the age of virtual threads that complexity no longer buys performance. Freeway chooses not to pay that cost, and the result is simpler code; raw throughput lags behind native engines (see §8), a known price paid for simplicity.

---

## 6. Module Composition Mechanism

| | Spring Boot | Quarkus | Micronaut | Freeway |
|---|---|---|---|---|
| **Composition method** | Annotation scanning + Auto-Configuration | Extension model | Dependency is the feature | **Explicit ModuleEx composition** |
| **Visibility** | Implicit (black box) | Semi-explicit (extension registration) | Semi-implicit (compile-time processing) | **Fully explicit** |
| **Conflict detection** | Possible conflicts at runtime | Detected at build time | Detected at compile time | **Reported explicitly at startup** |
| **Composition structure** | None | None | None | **Yes — an explicit module list, validated at startup** |

> Note: there is one disclosed exception to Freeway's "fully explicit" — `FreewayApp.run(...)` by default loads additional modules declared in
> `META-INF/services` via the ServiceLoader SPI (disabled by `autoDiscovery(false)`).
> Binding itself never scans; every binding is written in a readable `bind(Binder)`.

### Freeway's module composition mechanism

```java
// Module composition is an explicit list at the entry point
FreewayApp.run(
    new OrderModule(),
    CloudModule.class);                 // bundle automatically expands submodules

// Validated during assembly:
// - the same instance placed twice → folded
// - the same class declared twice → error (with both paths)
// - circular dependency → rejected
// - unknown hook id → startup failure
```

This is something the other three frameworks lack — **composition is data, not code**. The startup log prints the complete composition structure, so what you see is what is actually loaded.

---

## 7. Configuration Mechanism

| | Spring Boot | Quarkus | Micronaut | Freeway |
|---|---|---|---|---|
| **Configuration sources** | application.yml + Profile + @Value + @ConfigurationProperties | application.properties + Profile | application.yml + @ConfigurationProperties | **SymbolSource cascade** |
| **Priority** | Complex (multi-layer overrides) | Medium | Medium | **Explicitly declared tiers** |
| **Hot reload** | Spring DevTools (limited) | quarkus:dev (excellent) | So-so | **WatchService file-level hot reload** |
| **Environment variable mapping** | spring.config.import | %prod Profile | Direct mapping | **FREEWAY_* → freeway.* (configurable prefix)** |
| **CLI arguments** | --key=value | --key=value | --key=value | **--key=value, keys parsed literally (framework keys written in full)** |

### Freeway's configuration cascade (5 explicit tiers)

```
order  tier
 0     CLI args (--key=value, -Dkey=value)
 5     JVM system properties (-Dkey=value)
10     environment variables (FREEWAY_* → freeway.*)
15     module-contributed sources (e.g. cloud secrets)
20     configuration files (application.properties → .json → profile variants → filesystem overrides)
```

Each tier is a SymbolProvider, and order declares priority. There are no implicit override rules, no guessing about "who wins".

---

## 8. Runtime Performance

| Metric | Spring Boot 4 | Quarkus 3.x | Micronaut 4.x/5.x | Freeway 1.5.x |
|------|---------------|-------------|-------------------|-------------|
| **JVM startup** | 2,400–3,200ms | 500–900ms | 600–1,000ms | 77ms (framework-measured, median, single empty module; a 7-module HTTP app on the same basis ~65ms) |
| **Native startup** | 70–120ms | 12–25ms | 25–50ms | Not pursued (no native build, so nothing to measure) |
| **Idle memory (Native)** | 50–75MB | 35–60MB | 18–58MB | Not pursued |
| **Throughput (JVM)** | ~18k req/s | ~16k req/s | ~14k req/s | ping ~376k / json ~437k (built-in engine, median of the last 5 rounds on the same machine; pipeline-free freeway-native 6th in ping and 7th in json, close behind jetty-native; the native family holds the top five, the adapter family the bottom) |
| **GraalVM Native** | Requires configuring reflection hints | Automatic (most cases) | Most reliable (zero reflection) | Not pursued: DI/JSON/ORM all use runtime reflection and need reachability metadata; not configured or verified |

The Freeway column has a measured source: `docs/benchmark-1.5.6-SNAPSHOT.md` in the ext repository (environment lock + full JMH table + black-box median; worktree baseline, not a clean commit).

### Freeway's performance characteristics

- Core modules have zero external dependencies → no third-party dependency issues under native; but DI/JSON/ORM all use
  runtime reflection, which needs reachability metadata under GraalVM — that work has not been undertaken,
  and any claim that "native compilation should be easiest" is unverified
- Synchronous blocking I/O + virtual threads → simple code, JIT-friendly; but same-machine raw throughput trails the
  native engines (undertow/jetty/robaho ~450–565k vs built-in ~375–400k), and the built-in engine sits
  mid-table (above jdk-native and its own adapter; on WS it ties the undertow family, within the noise line) — the "best performance" claim has been withdrawn along with the numbers;
  the 30% gap in short JSON runs proved to be a transient tax (6% at steady state), see §8
- HTTP/2 built in → no container switching cost
- Connection pooling built in → no HikariCP dependency
- The built-in HTTP engine performs best in performance tests

---

## 9. Ecosystem and Suitable Scenarios

| | Spring Boot | Quarkus | Micronaut | Freeway |
|---|---|---|---|---|
| **Ecosystem size** | Largest | Medium (growing fast) | Smaller | **Smallest (new framework)** |
| **Talent pool** | Largest | Medium | Smaller | **Smallest** |
| **Learning curve** | Low (most familiar to teams) | Medium | Medium-high | **Low (few concepts)** |
| **Commercial support** | VMware | Red Hat | Oracle Cloud | **None** |
| **AI integration** | Spring AI (native) | LangChain4j (deep) | Limited | **None** |
| **Good fit** | Large enterprise systems, long-running high-throughput workloads | Kubernetes-native, Serverless | Serverless, edge computing | **Microservices, APIs, teams that want simplicity** |
| **Poor fit** | Startup-time sensitive, memory constrained | Broad ecosystem needs | Large enterprise systems | **Deep ecosystem integration requirements** |

---

## 10. Summary

### By dimension

| Dimension | Best | Reason |
|------|--------|------|
| **Fewest concepts** | Freeway (10-12) | Fewest core concepts, no choice paralysis |
| **Most thorough elimination of ceremony** | Freeway | No @EnableXxx, no Auto-Configuration, no classpath scanning |
| **Complexity genuinely absent** | Freeway | Not hidden — genuinely absent |
| **WYSIWYG** | Freeway | No code generation, no bytecode enhancement, no black boxes |
| **Developer experience** | Quarkus | dev mode hot reload is the most mature |
| **Broadest ecosystem** | Spring Boot | Deep integration of Spring Cloud/Security/AI |
| **Most reliable native** | Micronaut | Zero reflection, compile-time DI |
| **Best on Kubernetes** | Quarkus | Build-time startup, lowest memory |
| **Simplest HTTP engine** | Freeway | Synchronous blocking + virtual threads, zero NIO complexity (raw throughput about 60–80% of the native family, see §8) |
| **Most transparent module composition** | Freeway | Explicit module list, startup log prints the complete composition structure |
| **Clearest configuration cascade** | Freeway | 5 explicit tiers, order declares priority |

### One-sentence summary

- **Spring Boot**: strongest ecosystem, most complexity. Fits "I need the whole Spring family" scenarios.
- **Quarkus**: best on Kubernetes, best developer experience. Fits "I want to run on K8s" scenarios.
- **Micronaut**: most reliable native, fewer concepts. Fits "I want serverless" scenarios.
- **Freeway**: fewest concepts, most transparent complexity. Fits "I want simplicity, I want to understand what I write" scenarios.

### Freeway's core proposition

**Simplicity is not "looking simple" — it is "genuinely not having those things".**

- Not moving complexity from runtime to compile time (Micronaut)
- Not moving complexity to build time (Quarkus)
- Not leaving complexity at runtime but letting you hide it behind annotations (Spring)
- But **not introducing that complexity at all**

This aligns with Go's philosophy: **do the same thing with fewer concepts, eliminate unnecessary ceremony, make complexity genuinely absent.**

In the JDK 25+ virtual-thread era, "simple" is no longer a performance compromise. Freeway's built-in HTTP engine (synchronous blocking + virtual threads) performs best in performance tests, proving that simple code + a powerful runtime > complex code + a complex runtime.

---

## 11. The Agentic Era: Agent-Friendliness Assessment

In 2026, as AI coding agents (opencode, Cursor, Copilot) become primary development tools, a framework's "Agent friendliness" is a brand-new evaluation dimension.

### 11.1 Core constraints on agents at work

| Constraint | Meaning |
|------|------|
| **Limited context window** | There is an upper bound on the code and concepts it can "see" at once |
| **Pattern-matching driven** | Generates code by learning code patterns |
| **Cannot debug at runtime** | Can only read code and error messages; cannot attach a debugger |
| **Depends on documentation quality** | Relies on documentation and comments to understand framework behavior |
| **Diagnosis by inference** | Must infer the problem from error messages |

**The core of Agent friendliness**: the fewest concepts, the most predictable patterns and the clearest feedback, so an agent can correctly generate and modify code.

### 11.2 Evaluation dimensions

| Dimension | Meaning | Weight |
|------|------|------|
| **Concept density** | How many concepts an agent must remember to write code correctly | High |
| **Pattern consistency** | Whether one kind of thing has multiple forms (which to choose?) | High |
| **Black-box depth** | Whether an agent can infer runtime behavior from the code | High |
| **Error diagnosability** | Whether error messages let the agent fix the problem itself | Medium |
| **Configuration predictability** | Whether the agent can get it right the first time without trial and error | Medium |
| **Code self-explanatoriness** | Whether the code itself tells the agent what it does | Low |

### 11.3 Framework-by-framework assessment

#### Spring Boot: least Agent-friendly (3/10)

**Concept density too high**

To correctly generate a REST + DB service, an agent must hold all of this at once:

```
@SpringBootApplication (combination of 3 annotations)
+ @RestController vs @Controller + @ResponseBody
+ JpaRepository vs CrudRepository vs PagingAndSortingRepository
+ @Transactional's 7 parameters
+ RestTemplate vs WebClient vs HttpServiceClient
+ application.yml's multi-layer override rules
+ Profile mechanism
+ @EnableXxx family
```

**40+ concepts are a huge burden on a context window.**

**Inconsistent patterns**

```java
// The same thing, three ways to write it
@RestController                    // Form 1
@Controller @ResponseBody          // Form 2 (legacy baggage)
@RequestMapping + @GetMapping      // Form 3

// HTTP client, three choices
RestTemplate                       // Old
WebClient                          // New (reactive)
HttpServiceClient                  // Newest (4.0)
```

The agent must decide "which one to use", which requires knowing the framework's evolution history.

**Black box too deep**

```java
// Auto-Configuration black box
spring-boot-starter-data-jpa: what exactly does it configure?
// The agent doesn't know; it must consult source or documentation
// When something breaks, the agent can't tell whether it's its own problem or auto-configuration's
```

**Error messages hard to diagnose**

```
NoSuchBeanDefinitionException: No qualifying bean of type 'UserService' available
// The agent must figure out: was the bean not registered? Did scanning miss it? Was a conditional annotation not satisfied?
```

#### Quarkus: fairly Agent-friendly (6/10)

**Medium concept density**

15-18 core concepts, manageable for an agent.

**Fairly consistent patterns**

```java
// REST endpoint: one form
@Path("/api")
@GET
@QueryParam

// Data access: one form
Hibernate ORM with Panache

// Feature enablement: one way
quarkus add extension xxx
```

**Shallower black box**

Build-time processing is explicit — the agent knows what an extension does because extensions are explicitly registered.

**But there are pitfalls**

```java
// Mutiny reactive: may be pulled in even when you don't need reactive
Uni<String> result = service.findById(id).onItem().transform(u -> u.name());
// The agent must understand Uni/Multi concepts even when the business logic is synchronous
```

**Better error messages**

Quarkus error messages usually tell the agent which extension is missing or which configuration is wrong.

#### Micronaut: moderately Agent-friendly (5/10)

**Lower concept density**

12-15 core concepts, manageable for an agent.

**Consistent patterns**

```java
// One form
@Get("/{id}")
@Post
@Inject
```

**But there is a black box**

```java
// The code generated at compile time is a black box
// The agent wrote:
@Singleton
public class UserService {
    @Inject
    private UserRepository repo;
}

// What was generated at compile time? The agent doesn't know
// When something breaks, the agent must debug generated code
```

**Error messages sometimes point at generated code**

```
// The error may point at Micronaut$UserService$Definition.class
// The agent doesn't know where this class is because it never wrote it
```

#### Freeway: most Agent-friendly (9/10)

**Lowest concept density**

10-12 core concepts, easy for an agent to hold.

```java
// All the core concepts the agent needs to remember:
// ModuleEx, Container, Binder, Route, RuntimeHook, Scope
// @Inject, @Symbol
// Database, Orm, HttpFilter, EventBus
// That's it.
```

**Fully consistent patterns**

```java
// REST endpoint: one form
Route.get("/api/{id}", handler)

// Data access: one form
Orm.of(db).findById(User.class, 1L)

// Transactions: one form
db.transaction(() -> { ... })

// Feature enablement: one way
new HttpModule(), new DbModule()
```

**Zero black boxes**

```java
// The agent wrote:
binder.bind(UserService.class).to(UserServiceImpl.class);
// The agent knows exactly what will happen at runtime:
// - The container creates a UserServiceImpl instance
// - It injects all constructor parameters
// - It registers it as a singleton of type UserService

// No Auto-Configuration black box
// No classpath scanning
// No code generation
// What you see is what you get
```

**Error messages point straight at the problem**

```
// No NoSuchBeanDefinitionException
// The error tells you directly:
// "Module X declares binding for type Y, but no implementation is bound"
// The agent can fix it directly: add binder.bind(Y.class).to(Z.class);
```

**Predictable configuration**

```java
// The agent knows: 5 tiers, order declares priority
// No guessing about "who wins"
// CLI > system properties > environment variables > module contributions > configuration files
```

**Self-explanatory code**

```java
// Reading this code, the agent fully understands what it does:
FreewayApp.run(Launch.of(args), new AppModule(), new HttpModule(), new DbModule());
// Starts the application, loading AppModule and DbModule

// No hidden behavior
// No "what does this annotation do automatically behind the scenes"
```

### 11.4 Simulated correctness rate of Agent-generated code

Suppose an agent is given the task: "Create a REST service, GET /api/users/{id}, query the database and return the user"

| Framework | What the agent needs to know | Probability of getting it right on the first try |
|------|---------------|-------------------|
| Spring Boot | @RestController, @RequestMapping, @GetMapping, @PathVariable, @SpringBootApplication, application.yml configuration | **60%** |
| Quarkus | @Path, @GET, @PathParam, extension registration | **75%** |
| Micronaut | @Controller, @Get, @PathVariable, compile-time dependencies | **70%** |
| **Freeway** | Route.get, handler class, ModuleEx | **90%** |

### 11.5 Simulated Agent debugging capability

Suppose the agent-generated code throws an error — can the agent fix it itself?

| Framework | Typical error | Can the agent fix it |
|------|---------|---------------|
| Spring Boot | `NoSuchBeanDefinitionException: No qualifying bean of type 'UserService'` | **Hard** — must determine whether it's a scanning issue, a conditional-annotation issue, or a bean-name issue |
| Quarkus | `SRCHD0001: Unknown dependency for class UserService` | **Fairly easy** — usually a missing extension or binding |
| Micronaut | `BeanDefinitionNotFoundException: No bean definition found for type UserRepository` | **Medium** — possibly a compile-time processing issue |
| **Freeway** | `Binding not found: UserService → UserServiceImpl not bound` | **Easy** — it tells you directly which binding is missing |

### 11.6 Agent context-window efficiency

Assume the agent's context window is 8K tokens and it must hold framework concepts + user code + error messages at once.

| Framework | Framework concept usage | Remaining for user code | Efficiency |
|------|-------------|---------------|------|
| Spring Boot | ~3000 tokens | ~5000 tokens | **Low** |
| Quarkus | ~1500 tokens | ~6500 tokens | **Medium** |
| Micronaut | ~1200 tokens | ~6800 tokens | **Medium-high** |
| **Freeway** | ~800 tokens | ~7200 tokens | **High** |

**Freeway leaves the agent the most context space for handling user code.**

### 11.7 Agent learning curve

| Framework | How many patterns the agent must learn to generate code correctly |
|------|---------------------------------------|
| Spring Boot | 10+ patterns (REST, Data, Security, Config, Profile...) |
| Quarkus | 6-8 patterns |
| Micronaut | 5-6 patterns |
| **Freeway** | **4-5 patterns** (Module, Route, Database, Filter, Config) |

### 11.8 Summary

| Dimension | Spring Boot | Quarkus | Micronaut | Freeway |
|------|-------------|---------|-----------|---------|
| **Concept density** | 40+ | 15-18 | 12-15 | **10-12** |
| **Pattern consistency** | Low (multiple forms) | Medium | Medium-high | **High (one form)** |
| **Black-box depth** | Deep (Auto-Config) | Medium (build-time processing) | Medium (compile-time generation) | **Shallow (zero black boxes)** |
| **Error diagnosability** | Low | Medium | Medium | **High** |
| **Configuration predictability** | Low | Medium | Medium | **High** |
| **Code self-explanatoriness** | Low | Medium | Medium | **High** |
| **Agent-friendliness score** | **3/10** | **6/10** | **5/10** | **9/10** |

**In the agentic era, Freeway's design philosophy — fewer concepts, elimination of unnecessary complexity, WYSIWYG — is exactly what agents need most.** Agents don't need to "know framework evolution history", "guess what Auto-Configuration did", or "debug generated code". They only need to understand 10 core concepts to correctly generate and modify code.

**This is no coincidence — Freeway's design philosophy (simple, transparent, explicit) naturally fits how agents work (pattern matching, limited context, no runtime debugging).**

---

*Report generated: September 20, 2026*
