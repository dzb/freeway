package com.jujin.freeway.bench.cli;

import com.jujin.freeway.bench.model.BenchmarkResult;
import com.jujin.freeway.bench.model.BenchmarkRun;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.Orm;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@code bench compare} — compares two benchmark runs.
 *
 * <p>Arguments:
 * <pre>
 * --from=<run-id>      baseline run ID (default: latest - 1)
 * --to=<run-id>        candidate run ID (default: latest)
 * </pre>
 */
public final class CompareCommand implements Command {

    @Override
    public void run(Context ctx) throws Exception {
        var container = ctx.container();
        var db = container.get(Database.class);
        var coercer = container.get(Coercer.class);
        var orm = new Orm(db, coercer);
        var json = new JsonCodecDefault();

        // Determine run IDs
        var allRuns = orm.findAll(BenchmarkRun.class, "id ASC", 0, 0);
        if (allRuns.isEmpty()) {
            System.out.println("No runs found.");
            return;
        }

        int toId = ctx.getInt("to", (int) allRuns.getLast().id());
        var toRun = orm.findById(BenchmarkRun.class, (long) toId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + toId));

        // Auto-detect baseline: if --from not specified, find best previous run
        // with the same engine+scenario+concurrency as the candidate
        int fromId;
        if (ctx.args().containsKey("from")) {
            fromId = ctx.getInt("from", 0);
        } else {
            // Find best prior run with matching parameters
            fromId = db.query(
                "SELECT r.id FROM bench_runs r JOIN bench_results res ON r.id = res.run_id "
                + "WHERE r.engine = ? AND r.scenario = ? AND r.concurrency = ? "
                + "AND r.id < ? GROUP BY r.id ORDER BY MAX(res.score) DESC LIMIT 1",
                toRun.engine(), toRun.scenario(), toRun.concurrency(), toId)
                .one(Integer.class)
                .orElse((int) allRuns.getFirst().id());
        }

        var fromRun = orm.findById(BenchmarkRun.class, (long) fromId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + fromId));

        // Fetch results
        var fromResults = db.query(
            "SELECT * FROM bench_results WHERE run_id = ?", fromId)
            .list(BenchmarkResult.class);
        var toResults = db.query(
            "SELECT * FROM bench_results WHERE run_id = ?", toId)
            .list(BenchmarkResult.class);

        // Index by benchmark name — pick last result if duplicates (multiple run iterations)
        var fromIndex = fromResults.stream()
            .collect(Collectors.toMap(BenchmarkResult::benchmark, r -> r, (a, b) -> b));
        var toIndex = toResults.stream()
            .collect(Collectors.toMap(BenchmarkResult::benchmark, r -> r, (a, b) -> b));

        // Print header
        System.out.println();
        System.out.println("## Compare: Run #" + fromId + " (baseline) vs Run #" + toId + " (candidate)");
        System.out.println();
        System.out.printf("| %-28s | %12s %12s %7s | %12s %12s %7s | %s |%n",
            "Benchmark", "RPS", "p50", "p95", "RPS", "p50", "p95", "ΔRPS");
        System.out.println("|" + "─".repeat(30) + "|" + "─".repeat(34) + "|" + "─".repeat(34) + "|" + "─".repeat(8) + "|");

        // Collect all unique benchmark names
        var allBenchmarks = new java.util.LinkedHashSet<String>();
        allBenchmarks.addAll(fromIndex.keySet());
        allBenchmarks.addAll(toIndex.keySet());

        for (String bench : allBenchmarks) {
            var f = fromIndex.get(bench);
            var t = toIndex.get(bench);

            String fromRps = f != null ? formatRps(f.score()) : "—";
            String toRps = t != null ? formatRps(t.score()) : "—";
            String fromP50 = f != null ? f.p50us() + "μs" : "—";
            String toP50 = t != null ? t.p50us() + "μs" : "—";
            String fromP95 = f != null ? f.p95us() + "μs" : "—";
            String toP95 = t != null ? t.p95us() + "μs" : "—";

            String delta;
            if (f != null && t != null) {
                double d = (t.score() - f.score()) / f.score() * 100;
                delta = String.format(Locale.ROOT, "%+.1f%%", d);
            } else {
                delta = "—";
            }

            System.out.printf("| %-28s | %12s %12s %7s | %12s %12s %7s | %s |%n",
                bench, fromRps, fromP50, fromP95, toRps, toP50, toP95, delta);
        }

        System.out.println();
        System.out.println("Baseline:  " + formatRun(fromRun));
        System.out.println("Candidate: " + formatRun(toRun));
    }

    private static String formatRps(double rps) {
        if (rps >= 1_000_000) return String.format(Locale.ROOT, "%.2fM", rps / 1_000_000);
        if (rps >= 1_000) return String.format(Locale.ROOT, "%.1fk", rps / 1_000);
        return String.format(Locale.ROOT, "%.0f", rps);
    }

    private static String formatRun(BenchmarkRun run) {
        return String.format("#%d %s/%s concurrency=%d requests=%d warmup=%d %s",
            run.id(), run.engine(), run.scenario(), run.concurrency(),
            run.requests(), run.warmup(),
            run.createdAt().toString().replace("T", " ").substring(0, 19));
    }
}
