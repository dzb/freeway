package com.jujin.freeway.bench.cli;

import com.jujin.freeway.ioc.Container;
import java.util.Map;

/**
 * A CLI command contributed to the bench application.
 * Implementations are registered via {@code binder.contribute(Command.class)}.
 */
@FunctionalInterface
public interface Command {

    /** Execute this command. */
    void run(Context ctx) throws Exception;

    /** Runtime context injected by the CLI dispatcher. */
    record Context(Container container, String command, Map<String, String> args) {

        /** Returns the value for a key, or the default if absent. */
        public String get(String key, String defaultValue) {
            return args.getOrDefault(key, defaultValue);
        }

        /** Returns an int value for a key, or the default if absent. */
        public int getInt(String key, int defaultValue) {
            String v = args.get(key);
            return v != null ? Integer.parseInt(v) : defaultValue;
        }
    }
}
