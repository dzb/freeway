# Freeway Benchmarks

This module keeps performance work separate from the normal test suite.
See [BENCHMARK_PROTOCOL.md](BENCHMARK_PROTOCOL.md) for the benchmark rules and reporting format.

## Prerequisites

Install the core reactor into your local Maven repository before running benchmarks:

```bash
mvn -pl freeway-commons,freeway-ioc,freeway-boot,freeway-http,freeway-db -am install -DskipTests "-Dgpg.skip=true"
```

## JMH Microbenchmarks

Run the microbenchmarks through the JMH launcher:

```bash
mvn -pl freeway-benchmark -am -DskipTests exec:java \
  -Dexec.mainClass=com.jujin.freeway.benchmarks.BenchmarkMain \
  -Dexec.args='-bm thrpt -f 0 -wi 5 -i 5 com.jujin.freeway.http.engine.HttpParserBenchmark'
```

Useful benchmark classes:

- `com.jujin.freeway.http.engine.HttpParserBenchmark` — HTTP/1.1 request parsing
- `com.jujin.freeway.http.engine.HttpContextOutputBenchmark` — response output (text, JSON, not-found)
- `com.jujin.freeway.http.engine.HttpContextLookupBenchmark` — header/query/param lookup
- `com.jujin.freeway.http.engine.FilterChainBenchmark` — full filter chain (timing → cors → health)
- `com.jujin.freeway.http.engine.JsonCodecBenchmark` — JSON serialization/deserialization
- `com.jujin.freeway.http.engine.ws.WebSocketFrameBenchmark` — WebSocket frame read/write/construct
- `com.jujin.freeway.http.route.RouteIndexBenchmark` — route matching (exact, param, wildcard)
- `com.jujin.freeway.http.body.MultipartFormBenchmark` — multipart form parsing

The microbenchmarks are the decision-grade inputs. The HTTP smoke harness is for
local validation and release gating, not for final performance claims.

If you want forked JMH runs (`-f > 0`), run them with an explicit benchmark
classpath. The plain `exec:java` sample above is the zero-fork path; it avoids
classpath drift and is the safest default for local iteration.

## HTTP Black-box Benchmark

Run the server benchmark with the built-in engine:

```bash
mvn -pl freeway-benchmark -am -DskipTests exec:java \
  -Dexec.mainClass=com.jujin.freeway.benchmarks.BenchmarkRunner \
  -Dbench.engine=freeway \
  -Dbench.requests=20000 \
  -Dbench.concurrency=32 \
  -Dbench.warmup=2000
```

Use `bench.engine=jdk-native` as the lower-bound baseline for comparison.
Run directly with `java -cp` for fully isolated classpath (avoids exec:java classpath issues):

```bash
mvn -pl freeway-benchmark -am process-classes -DskipTests
java --add-opens=java.base/java.lang=ALL-UNNAMED \
     --enable-native-access=ALL-UNNAMED \
     -cp "$(cat freeway-benchmark/target/benchmark.classpath);freeway-benchmark/target/classes" \
     -Dbench.engine=freeway -Dbench.requests=20000 -Dbench.concurrency=32 -Dbench.warmup=2000 \
     -Dbench.runs=3 -Dbench.fork=false \
     com.jujin.freeway.benchmarks.BenchmarkRunner
```

| Engine           | Description |
|------------------|-------------|
| `freeway`        | Freeway's built-in HTTP engine (`FreewayHttpEngine`) |
| `jdk-native`     | Bare JDK `com.sun.net.httpserver.HttpServer` (baseline) |
| `robaho-native`  | Robaho's `HttpServer` implementation (`robaho.net.httpserver`) |
| `undertow-native`| Native Undertow server (`io.undertow.Undertow`) |

## ServerHarness API

The `ServerHarness` supports three benchmark scenarios via `Engine` + `Scenario` enums:

```java
// Start a server
try (var h = ServerHarness.start(Engine.FREEWAY, Scenario.PING)) {
    int port = h.port();
    // ... send requests ...
}
```

| Scenario   | Method | Path | Response |
|------------|--------|------|----------|
| `PING`     | GET    | `/ping` | 200 "pong" text/plain |
| `JSON`     | GET    | `/api/resource` | 200 `{"id":1,"name":"test"}` |
| `ECHO_BODY`| POST   | `/echo` | 200 + request body echo |

To add a new scenario, add a case in `ServerHarness.freewayHandler()`,
`bareHandler()`, and `undertowHandler()`.
