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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code bench suite} — runs multiple benchmark combinations and generates a comparison report.
 *
 * <p>Arguments:
 * <pre>
 * --engines=freeway,robaho-native,undertow-native
 * --scenarios=ping,json
 * --concurrency=8,16
 * --requests=2000
 * --warmup=200
 * --runs=3
 * --output=report.md       (optional: write Markdown report to file)
 * </pre>
 *
 * <p>Each combination of (engine, scenario, concurrency) is run sequentially.
 * Results are written to SQLite and a comparison table is printed at the end.
 */
public final class SuiteCommand implements Command {

    @Override
    public void run(Context ctx) throws Exception {
        var container = ctx.container();
        var db = container.get(Database.class);
        var coercer = container.get(Coercer.class);
        var orm = new Orm(db, coercer);
        var eventBus = container.get(EventBus.class);
        var json = new JsonCodecDefault();

        // Parse configuration
        var engines = parseList(ctx.get("engines", "freeway"));
        var scenarios = parseList(ctx.get("scenarios", "ping"));
        var concurrencies = parseIntList(ctx.get("concurrency", "16"));
        int requests = ctx.getInt("requests", 2000);
        int warmup = ctx.getInt("warmup", 200);
        int runs = ctx.getInt("runs", 3);
        var modeStr = ctx.get("mode", "keepalive");
        if (modeStr.equalsIgnoreCase("long")) modeStr = "keepalive";
        var mode = "short".equalsIgnoreCase(modeStr)
            ? BenchRunner.Mode.SHORT : BenchRunner.Mode.KEEPALIVE;
        String outputPath = ctx.get("output", null);

        int total = engines.size() * scenarios.size() * concurrencies.length * runs;
        int done = 0;

        System.out.println("## Suite: " + ctx.args().toString());
        System.out.println();
        System.out.printf("Engines: %s | Scenarios: %s | Concurrency: %s | "
            + "Requests: %d | Warmup: %d | Runs: %d | Mode: %s%n",
            engines, scenarios, java.util.Arrays.toString(concurrencies),
            requests, warmup, runs, modeStr);
        System.out.printf("Total iterations: %d%n", total);
        System.out.println();

        // Collect all results for the final report
        var allResults = new ArrayList<SuiteResult>();

        for (var engine : engines) {
            var eng = ServerHarness.Engine.fromString(engine);
            for (var scenario : scenarios) {
                var scn = ServerHarness.Scenario.valueOf(scenario.toUpperCase());
                for (int concurrency : concurrencies) {
                    // Create run record
                    var run = BenchmarkRun.create(engine, scenario, concurrency,
                        requests, warmup, runs);
                    long runId = orm.insert(run).longKey();
                    eventBus.publish(new BenchEvent.RunStarted(run));

                    System.out.printf("### %s / %s concurrency=%d%n",
                        engine, scenario, concurrency);
                    System.out.println();

                    List<BenchRunner.IterationResult> iterationResults = new ArrayList<>();

                    try (var harness = ServerHarness.start(eng, scn)) {
                        int port = harness.port();
                        for (int r = 0; r < runs; r++) {
                            done++;
                            var ir = BenchRunner.run(port, concurrency, requests, warmup, scn, mode);
                            iterationResults.add(ir);

                            var result = BenchmarkResult.of(runId,
                                engine + "/" + scenario,
                                "keepalive", ir.rps(), 0, "req/s",
                                ir.p50us(), ir.p95us(), ir.p99us(), ir.errors());
                            orm.insert(result);
                            eventBus.publish(new BenchEvent.ResultCollected(result));

                            System.out.printf("  run %d/%d: rps=%.0f p50=%dus "
                                + "[%d/%d]%n",
                                r + 1, runs, ir.rps(), ir.p50us(), done, total);
                        }
                    }

                    // Pick median iteration as representative
                    var median = iterationResults.stream()
                        .sorted(Comparator.comparingDouble(BenchRunner.IterationResult::rps))
                        .skip(iterationResults.size() / 2)
                        .findFirst().orElseThrow();
                    allResults.add(new SuiteResult(engine, scenario, concurrency,
                        median.rps(), median.p50us(), median.p95us(), median.p99us()));
                    System.out.println();
                }
            }
        }

        // Print comprehensive comparison table
        System.out.println("## Suite Summary");
        System.out.println();
        System.out.printf("| %-16s | %-10s | %6s | %9s | %6s | %6s | %6s |%n",
            "Engine", "Scenario", "Concur", "RPS", "p50", "p95", "p99");
        System.out.println("|" + "─".repeat(18) + "|" + "─".repeat(12) + "|"
            + "─".repeat(8) + "|" + "─".repeat(11) + "|"
            + "─".repeat(8) + "|" + "─".repeat(8) + "|" + "─".repeat(8) + "|");

        for (var r : allResults) {
            System.out.printf("| %-16s | %-10s | %6d | %9s | %6s | %6s | %6s |%n",
                r.engine(), r.scenario(), r.concurrency(),
                formatRps(r.rps()), r.p50us() + "μs",
                r.p95us() + "μs", r.p99us() + "μs");
        }

        // Write report to file if requested
        if (outputPath != null && !outputPath.isBlank()) {
            var report = new StringBuilder();
            report.append("# Suite Report\n\n");
            report.append("| Engine | Scenario | Concur | RPS | p50 | p95 | p99 |\n");
            report.append("| --- | --- | ---: | ---: | ---: | ---: | ---: |\n");
            for (var r : allResults) {
                report.append(String.format(Locale.ROOT,
                    "| %s | %s | %d | %s | %dμs | %dμs | %dμs |\n",
                    r.engine(), r.scenario(), r.concurrency(),
                    formatRps(r.rps()), r.p50us(), r.p95us(), r.p99us()));
            }
            java.nio.file.Files.writeString(java.nio.file.Path.of(outputPath),
                report.toString(), java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("Report written to " + outputPath);
        }

        System.out.printf("%nSuite complete: %d iteration(s), %d total combinations.%n",
            done, engines.size() * scenarios.size() * concurrencies.length);
    }

    private record SuiteResult(String engine, String scenario, int concurrency,
                                double rps, long p50us, long p95us, long p99us) {}

    private static List<String> parseList(String value) {
        var list = new ArrayList<String>();
        for (var s : value.split(",")) {
            var trimmed = s.trim();
            if (!trimmed.isEmpty()) list.add(trimmed);
        }
        return list;
    }

    private static int[] parseIntList(String value) {
        var parts = value.split(",");
        var result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Integer.parseInt(parts[i].trim());
        }
        return result;
    }

    private static String formatRps(double rps) {
        if (rps >= 1_000_000) return String.format(Locale.ROOT, "%.2fM", rps / 1_000_000);
        if (rps >= 1_000) return String.format(Locale.ROOT, "%.1fk", rps / 1_000);
        return String.format(Locale.ROOT, "%.0f", rps);
    }
}
