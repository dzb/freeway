package com.jujin.freeway.boot;

public interface AppRuntime extends AutoCloseable {
    AppConfig config();

    AppState state();

    void start();

    default boolean isRunning() {
        return state() == AppState.RUNNING;
    }

    /** Convenience: resolve a service by type. */
    <T> T get(Class<T> type);

    /** Convenience: resolve a named service by type and id. */
    <T> T get(Class<T> type, String id);

    @Override
    void close();
}
