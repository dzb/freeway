# rest-db — HTTP + DB few-shot

A complete, runnable Freeway application in a handful of small files: one
module, one record entity, three routes, an in-memory H2 database. It is the
shortest path from nothing to a working CRUD service — use it as the template
when composing a new app.

## What it demonstrates

- **Explicit composition** — `RestDbDemo` places exactly three modules:
  `UserModule` (the app), `HttpModule`, `DbModule`. Nothing else can take
  part.
- **Annotation-driven schema** — the `User` record carries `@Table`/`@Id`/
  `@Column`; a `SchemaEntity` contribution plus `freeway.db.schema.auto`
  (on by default) creates the table before the HTTP server starts.
- **Routes as contributions** — three `Route` contributions, handler classes
  resolved from the container at startup (misconfigured handlers fail
  startup, not the first request).
- **Constructor injection** — handlers get `UserService`, which gets `Orm`
  and `Database` from `DbModule`.
- **Validation → 400, unknown id → 404, wrong method → 405 with `Allow`** —
  the framework's error mapping on every path.
- **Scoped transaction** — `UserService.create` wraps the insert in
  `db.transaction(...)`.

## Layout

| File | Role |
|---|---|
| `src/main/java/demo/RestDbDemo.java` | entry point — composes the three modules |
| `src/main/java/demo/UserModule.java` | bindings + `SchemaEntity` + `Route` contributions |
| `src/main/java/demo/User.java` | the entity (record + schema annotations) |
| `src/main/java/demo/CreateUser.java` | request body record (`@NotBlank` name) |
| `src/main/java/demo/UserService.java` | Orm CRUD inside `db.transaction` |
| `src/main/java/demo/*Handler.java` | one handler class per route |
| `src/main/resources/application.properties` | the three `freeway.db.*` keys |
| `src/test/java/demo/RestDbDemoTest.java` | boots the app and drives all of it over HTTP |

## Prerequisites

The demo resolves `com.jujin8.freeway:*:1.5.5` from the local
repository — install the core once from the repository root:

```bash
mvn install -DskipTests -Dgpg.skip=true
```

## Run the test (verifies everything)

```bash
cd demo/rest-db
mvn test
```

## Run the service

```bash
cd demo/rest-db
mvn package
java -jar target/rest-db-1.0-SNAPSHOT.jar
```

Then, in another terminal:

```bash
curl -s -X POST http://localhost:8080/api/users \
  -H 'Content-Type: application/json' \
  -d '{"name":"ada","age":36}'
# {"id":1,"name":"ada","age":36}

curl -s http://localhost:8080/api/users
# [{"id":1,"name":"ada","age":36}]

curl -s http://localhost:8080/api/users/1
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/api/users/999999   # 404
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE http://localhost:8080/api/users # 405
```

The database is in-memory (H2): stopping the process discards everything.
Swapping `freeway.db.url/username/password` in `application.properties` for a
real database is the only change a persistent deployment needs.
