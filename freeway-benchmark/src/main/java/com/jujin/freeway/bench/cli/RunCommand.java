package com.jujin.freeway.bench.cli;

import com.jujin.freeway.bench.event.BenchEvent;
import com.jujin.freeway.bench.model.BenchmarkResult;
import com.jujin.freeway.bench.model.BenchmarkRun;
import com.jujin.freeway.benchmarks.ServerHarness;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.Orm;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.EventBus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.List;
import java.util.Map;
import java.util.List;

/**
 * {@code bench run} — runs a black-box HTTP benchmark and persists results.
 *
 * <p>Arguments:
 * <pre>
 * --engine=freeway      server engine
 * --scenario=ping       benchmark scenario
 * --concurrency=32      concurrent connections
 * --requests=5000       requests per run
 * --warmup=500          warmup requests before measurement
 * --runs=3              number of measurement runs
 * </pre>
 */
public final class RunCommand implements Command {

    @Override
    public void run(Context ctx) throws Exception {
        var engine = ctx.get("engine", "freeway");
        var scenario = ctx.get("scenario", "ping");
        var modeStr = ctx.get("mode", "keepalive");
        int concurrency = ctx.getInt("concurrency", 32);
        if (modeStr.equalsIgnoreCase("long")) modeStr = "keepalive";
        int requests = ctx.getInt("requests", 5000);
        int warmup = ctx.getInt("warmup", 500);
        int runs = ctx.getInt("runs", 3);

        System.out.printf("bench run --engine=%s --scenario=%s --concurrency=%d "
            + "--requests=%d --warmup=%d --runs=%d%n",
            engine, scenario, concurrency, requests, warmup, runs);

        // Retrieve Database from container (provided by BenchDbModule)
        var container = ctx.container();
        var db = container.get(Database.class);
        var coercer = container.get(Coercer.class);
        var orm = new Orm(db, coercer);
        var eventBus = container.get(EventBus.class);

        // Create run record
        var run = BenchmarkRun.create(engine, scenario, concurrency,
            requests, warmup, runs);
        long runId = orm.insert(run).longKey();
        eventBus.publish(new BenchEvent.RunStarted(run));

        // Run the benchmark
        var eng = ServerHarness.Engine.fromString(engine);
        var scn = ServerHarness.Scenario.valueOf(scenario.toUpperCase());
        var mode = "short".equalsIgnoreCase(modeStr)
            ? BenchRunner.Mode.SHORT : BenchRunner.Mode.KEEPALIVE;
        var results = new ArrayList<BenchmarkResult>();

        try (var harness = ServerHarness.start(eng, scn)) {
            int port = harness.port();

            for (int r = 0; r < runs; r++) {
                System.out.printf("  run %d/%d ...%n", r + 1, runs);
                var ir = BenchRunner.run(port, concurrency, requests, warmup, scn, mode);

                System.out.printf("    rps=%.0f p50=%dus p95=%dus p99=%dus "
                    + "errors=%d%n", ir.rps(), ir.p50us(), ir.p95us(), ir.p99us(), ir.errors());

                var result = BenchmarkResult.of(runId, engine + "/" + scenario,
                    "keepalive", ir.rps(), 0, "req/s",
                    ir.p50us(), ir.p95us(), ir.p99us(), ir.errors());
                results.add(result);
                orm.insert(result);
                eventBus.publish(new BenchEvent.ResultCollected(result));
            }
        }

        eventBus.publish(new BenchEvent.RunCompleted(runId));
        System.out.printf("Done. Run #%d saved.%n", runId);

        // Print summary table
        System.out.println();
        System.out.println("| Run | RPS | p50 | p95 | p99 | Errors |");
        System.out.println("| --- | --: | --: | --: | --: | ----: |");
        for (int i = 0; i < results.size(); i++) {
            var r = results.get(i);
            System.out.printf("| %d | %.0f | %dμs | %dμs | %dμs | %d |%n",
                i + 1, r.score(), r.p50us(), r.p95us(), r.p99us(), r.errors());
        }
        if (results.size() > 1) {
            var median = results.stream()
                .sorted(java.util.Comparator.comparingDouble(BenchmarkResult::score))
                .skip(results.size() / 2)
                .findFirst().orElseThrow();
            System.out.printf("| **Median** | **%.0f** | **%dμs** | **%dμs** | **%dμs** | **%d** |%n",
                median.score(), median.p50us(), median.p95us(), median.p99us(), median.errors());
        }
	        System.out.println();
	
	        // Write JSON output if --output is specified
	        String outputPath = ctx.get("output", null);
	        if (outputPath != null && !outputPath.isBlank()) {
	            var jsonMap = new LinkedHashMap<String, Object>();
	            jsonMap.put("run_id", runId);
	            jsonMap.put("engine", engine);
	            jsonMap.put("scenario", scenario);
	            jsonMap.put("concurrency", concurrency);
	            jsonMap.put("requests", requests);
	            jsonMap.put("warmup", warmup);
	            jsonMap.put("runs", runs);
	
	            var runsList = new ArrayList<Map<String, Object>>();
	            for (int i = 0; i < results.size(); i++) {
	                var r = results.get(i);
	                var m = new LinkedHashMap<String, Object>();
	                m.put("run", i + 1);
	                m.put("rps", r.score());
	                m.put("p50_us", r.p50us());
	                m.put("p95_us", r.p95us());
	                m.put("p99_us", r.p99us());
	                m.put("errors", r.errors());
	                runsList.add(m);
	            }
	            jsonMap.put("results", runsList);
	
	            var json = new JsonCodecDefault().toJson(jsonMap);
	            Files.writeString(Path.of(outputPath), json, StandardCharsets.UTF_8);
	            System.out.println("Results written to " + outputPath);
	        }
	    }
}
