package com.jujin.freeway.bench;

import com.jujin.freeway.bench.cli.CliModule;
import com.jujin.freeway.bench.db.BenchDbModule;
import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;

/**
 * {@code bench} — Freeway-powered CLI benchmark application.
 *
 * <p>Usage:
 * <pre>
 * bench run --engine=freeway --concurrency=32 --requests=5000
 * bench list --limit=10
 * </pre>
 *
 * <p>Persistence: SQLite database ({@code bench.db}) created in the working directory.
 *
 * <p>This is a companion application that showcases Freeway's IoC, DbModule,
 * EventBus, and Boot capabilities while serving as a practical performance tool.
 */
public final class BenchApp {

    public static void main(String[] args) {
        // Configure SQLite before any module loads
        System.setProperty("freeway.db.url", "jdbc:sqlite:bench.db");
        System.setProperty("freeway.db.username", "sa");
        System.setProperty("freeway.db.password", "");
        System.setProperty("freeway.db.pool.max-size", "1");
        System.setProperty("freeway.db.pool.min-idle", "0");

        // Use random port so CLI doesn't conflict with anything
        System.setProperty("freeway.web.server.port", "0");

        AppRuntime app = FreewayApp.run(new BenchDbModule(), new CliModule());
        try {
            CliModule.dispatch(app.container(), args);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            app.close();
        }
    }
}
