# Freeway Benchmarks

This module keeps performance work separate from the normal test suite.
See [BENCHMARK_PROTOCOL.md](BENCHMARK_PROTOCOL.md) for the benchmark rules and reporting format.

## Prerequisites

Install the core reactor and the transport adapters into your local Maven
repository before running the smoke benchmark:

```bash
cd freeway
mvn -pl freeway-commons,freeway-ioc,freeway-boot,freeway-http,freeway-db -am install -DskipTests "-Dgpg.skip=true"

cd freeway-ext
mvn -pl freeway-http-robaho,freeway-http-undertow -am install -DskipTests "-Dgpg.skip=true"
```

## JMH

Run the microbenchmarks through the JMH launcher:

```bash
mvn -pl freeway-benchmarks -am -DskipTests exec:java \
  -Dexec.mainClass=com.jujin.freeway.benchmarks.BenchmarkMain \
  -Dexec.args='-bm thrpt -f 0 -wi 5 -i 5 com.jujin.freeway.http.engine.HttpParserBenchmark'
```

Useful benchmark classes:

- `com.jujin.freeway.http.engine.HttpParserBenchmark`
- `com.jujin.freeway.http.route.RouteIndexBenchmark`
- `com.jujin.freeway.http.body.MultipartFormBenchmark`
- `com.jujin.freeway.http.HttpContextLookupBenchmark`

The microbenchmarks are the decision-grade inputs. The HTTP smoke harness is for
local validation and release gating, not for final performance claims.

If you want forked JMH runs (`-f > 0`), run them with an explicit benchmark
classpath. The plain `exec:java` sample above is the zero-fork path; it avoids
classpath drift and is the safest default for local iteration.

## Decision-grade HTTP benchmark

Use the separate-process benchmark when comparing engines:

```bash
mvn -pl freeway-benchmarks -am -DskipTests exec:java \
  -Dexec.mainClass=com.jujin.freeway.benchmarks.http.HttpEngineDecisionMain \
  -Dbench.engine=freeway \
  -Dbench.requests=2000 \
  -Dbench.concurrency=2 \
  -Dbench.warmup=200 \
  -Dbench.runs=3
```

This runner starts the server in a child JVM, runs the client load in another
child JVM, and reports the median of three independent runs.
Use `bench.engine=jdk` as the lower-bound baseline, and compare freeway against
`robaho` or `undertow` on the same request shape.

## HTTP smoke benchmark

Run the black-box server benchmark with a single engine per JVM:

```bash
mvn -pl freeway-benchmarks -am -DskipTests exec:java \
  -Dexec.mainClass=com.jujin.freeway.benchmarks.http.HttpEngineSmokeMain \
  -Dbench.engine=freeway \
  -Dbench.requests=20000 \
  -Dbench.concurrency=32 \
  -Dbench.warmup=2000
```

Supported engines:

- `freeway`
- `jdk`
- `robaho`
- `undertow`
