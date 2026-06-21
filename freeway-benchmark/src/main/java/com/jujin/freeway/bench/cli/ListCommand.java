package com.jujin.freeway.bench.cli;

import com.jujin.freeway.bench.model.BenchmarkRun;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.Orm;
import java.util.List;

/**
 * {@code bench list} — shows recent benchmark runs from the SQLite database.
 *
 * <p>Arguments:
 * <pre>
 * --limit=10    max rows to show (default: 10)
 * --engine=     filter by engine (optional)
 * </pre>
 */
public final class ListCommand implements Command {

    @Override
    public void run(Context ctx) throws Exception {
        int limit = ctx.getInt("limit", 10);
        String engineFilter = ctx.get("engine", null);

        var container = ctx.container();
        var db = container.get(Database.class);
        var coercer = container.get(Coercer.class);
        var orm = new Orm(db, coercer);

        String sql = "SELECT id, engine, scenario, concurrency, requests, "
            + "warmup, runs, commit_sha, jdk_info, os_info, cpu_info, created_at "
            + "FROM bench_runs";
        if (engineFilter != null) {
            sql += " WHERE engine = ?";
        }
        sql += " ORDER BY created_at DESC LIMIT ?";

        List<BenchmarkRun> runs;
        if (engineFilter != null) {
            runs = db.query(sql, engineFilter, limit).list(BenchmarkRun.class);
        } else {
            runs = db.query(sql, limit).list(BenchmarkRun.class);
        }

        if (runs.isEmpty()) {
            System.out.println("No benchmark runs found.");
            return;
        }

        System.out.printf("%-4s %-16s %-10s %-6s %s%n",
            "ID", "Engine", "Scenario", "Concur", "Created");
        System.out.println("─".repeat(60));
        for (var r : runs) {
            System.out.printf("%-4d %-16s %-10s %-6d %s%n",
                r.id(), r.engine(), r.scenario(), r.concurrency(),
                r.createdAt().toString().replace("T", " ").substring(0, 19));
        }
    }
}
