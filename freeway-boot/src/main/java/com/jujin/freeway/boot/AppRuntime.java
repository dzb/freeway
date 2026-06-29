package com.jujin.freeway.boot;

import com.jujin.freeway.ioc.Container;

public interface AppRuntime extends AutoCloseable {
    Container container();

    AppConfig config();

    AppState state();

    void start();

    default boolean isRunning() {
        return state() == AppState.RUNNING;
    }

    default <T> T get(Class<T> type) {
        return container().get(type);
    }

    default <T> T get(Class<T> type, String id) {
        return container().get(type, id);
    }

    /** Enable strict close mode: shutdown exceptions are printed to stderr
     *  in addition to SLF4J, ensuring they are visible even during JVM shutdown. */
    default void setStrictClose(boolean strict) {
    }

    @Override
    void close();
}
