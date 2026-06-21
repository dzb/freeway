package com.jujin.freeway.bench.cli;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Module2;

/**
 * Registers all CLI command implementations as contributions.
 * New commands are added to {@link #bind(Binder)}.
 */
public final class CliModule implements Module2 {

    @Override
    public void bind(Binder binder) {
        var cmds = binder.contribute(Command.class);
        cmds.add(new RunCommand());
        cmds.add(new ListCommand());
        cmds.add(new CompareCommand());
        cmds.add(new HistoryCommand());
        cmds.add(new SuiteCommand());
    }

    /**
     * Dispatches the first CLI argument to the matching Command.
     * Called by {@link BenchApp} after the container starts.
     */
    public static void dispatch(Container container, String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: bench <command> [--key=value ...]");
            System.out.println("Commands:");
            System.out.println("  run      Run a benchmark");
            System.out.println("  suite    Run multiple engine/scenario/concurrency combinations");
            System.out.println("  list     Show recent benchmark runs");
            System.out.println("  compare  Compare two benchmark runs");
            System.out.println("  history  Show performance trend over time");
            return;
        }

        String commandName = args[0];
        var cmdArgs = java.util.Map.<String, String>of();
        if (args.length > 1) {
            var map = new java.util.LinkedHashMap<String, String>();
            for (int i = 1; i < args.length; i++) {
                String a = args[i];
                if (a.startsWith("--")) {
                    int eq = a.indexOf('=');
                    if (eq > 0) {
                        map.put(a.substring(2, eq), a.substring(eq + 1));
                    } else {
                        map.put(a.substring(2), "true");
                    }
                }
            }
            cmdArgs = java.util.Map.copyOf(map);
        }

        var ctx = new Command.Context(container, commandName, cmdArgs);

        var commands = container.extension(Command.class).all();
        for (var cmd : commands) {
            if (cmd.getClass().getSimpleName()
                    .equalsIgnoreCase(commandName + "Command")) {
                cmd.run(ctx);
                return;
            }
        }

        System.err.println("Unknown command: " + commandName);
        System.exit(1);
    }
}
