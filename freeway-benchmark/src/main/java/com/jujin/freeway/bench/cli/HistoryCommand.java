package com.jujin.freeway.bench.cli;

import com.jujin.freeway.bench.model.BenchmarkResult;
import com.jujin.freeway.bench.model.BenchmarkRun;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.Orm;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@code bench history} — shows performance trend for a benchmark.
 *
 * <p>Arguments:
 * <pre>
 * --bench=<name>   benchmark name filter (e.g. "freeway/ping")
 * --days=<n>       only show runs from the last N days (default: 30)
 * --engine=<name>  filter by engine (optional)
 * </pre>
 */
public final class HistoryCommand implements Command {

    @Override
    public void run(Context ctx) throws Exception {
        var container = ctx.container();
        var db = container.get(Database.class);
        var coercer = container.get(Coercer.class);
        var orm = new Orm(db, coercer);

        String benchFilter = ctx.get("bench", null);
        String engineFilter = ctx.get("engine", null);
        int days = ctx.getInt("days", 30);

        // Fetch runs within the time window
        List<BenchmarkRun> runs;
        if (engineFilter != null) {
            runs = db.query(
                "SELECT * FROM bench_runs WHERE engine = ? AND created_at >= datetime('now', ?) ORDER BY created_at ASC",
                engineFilter, "-" + days + " days")
                .list(BenchmarkRun.class);
        } else {
            runs = db.query(
                "SELECT * FROM bench_runs WHERE created_at >= datetime('now', ?) ORDER BY created_at ASC",
                "-" + days + " days")
                .list(BenchmarkRun.class);
        }

        if (runs.isEmpty()) {
            System.out.println("No runs found in the last " + days + " days.");
            return;
        }

        // Collect run IDs
        var runIds = runs.stream().map(BenchmarkRun::id).toList();

        // Fetch results for these runs
        String placeholders = runIds.stream().map(id -> "?").collect(Collectors.joining(","));
        String sql = "SELECT * FROM bench_results WHERE run_id IN (" + placeholders + ")";
        if (benchFilter != null) {
            sql += " AND benchmark = ?";
        }
        sql += " ORDER BY run_id ASC";

        List<BenchmarkResult> results;
        if (benchFilter != null) {
            var params = new Object[runIds.size() + 1];
            for (int i = 0; i < runIds.size(); i++) params[i] = runIds.get(i);
            params[runIds.size()] = benchFilter;
            results = db.query(sql, params).list(BenchmarkResult.class);
        } else {
            results = db.query(sql, runIds.toArray()).list(BenchmarkResult.class);
        }

        if (results.isEmpty()) {
            System.out.println("No results found for the given filters.");
            return;
        }

        // Group by benchmark name
        var byBenchmark = results.stream()
            .collect(Collectors.groupingBy(BenchmarkResult::benchmark));

        for (var entry : byBenchmark.entrySet()) {
            String benchName = entry.getKey();
            var benchResults = entry.getValue();

            System.out.println();
            System.out.println("## " + benchName);
            System.out.println();
            System.out.printf("| %-4s | %-20s | %9s | %6s | %6s | %6s | %s |%n",
                "Run", "Created", "RPS", "p50", "p95", "p99", "Δ vs best");
            System.out.println("|" + "─".repeat(6) + "|" + "─".repeat(22) + "|" + "─".repeat(11) + "|"
                + "─".repeat(8) + "|" + "─".repeat(8) + "|" + "─".repeat(8) + "|" + "─".repeat(12) + "|");

            // Find best score for this benchmark
            double bestScore = benchResults.stream()
                .mapToDouble(BenchmarkResult::score)
                .max().orElse(1);

            // Find corresponding run for each result
            var runIndex = runs.stream().collect(Collectors.toMap(BenchmarkRun::id, r -> r));

            for (var r : benchResults) {
                var run = runIndex.get(r.runId());
                String created = run != null
                    ? run.createdAt().toString().replace("T", " ").substring(0, 16)
                    : "—";
                double delta = (r.score() - bestScore) / bestScore * 100;

                System.out.printf("| %-4d | %-20s | %9s | %6s | %6s | %6s | %+10.1f%% |%n",
                    r.runId(), created, formatRps(r.score()),
                    r.p50us() + "μs", r.p95us() + "μs", r.p99us() + "μs", delta);
            }
        }

        System.out.println();
        System.out.printf("Showing %d run(s) from the last %d day(s).%n", runs.size(), days);
    }

    private static String formatRps(double rps) {
        if (rps >= 1_000_000) return String.format(Locale.ROOT, "%.2fM", rps / 1_000_000);
        if (rps >= 1_000) return String.format(Locale.ROOT, "%.1fk", rps / 1_000);
        return String.format(Locale.ROOT, "%.0f", rps);
    }
}
