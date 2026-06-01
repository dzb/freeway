package com.jujin.freeway.boot;

import com.jujin.freeway.ioc.Container;

public interface AppRuntime extends AutoCloseable {
    Container container();

    AppConfig config();

    AppState state();

    void start();

    default boolean running() {
        return state() == AppState.RUNNING;
    }

    default <T> T get(Class<T> type) {
        return container().get(type);
    }

    default <T> T get(Class<T> type, String id) {
        return container().get(type, id);
    }

    @Override
    void close();
}
