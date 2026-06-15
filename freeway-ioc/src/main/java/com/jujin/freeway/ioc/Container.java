package com.jujin.freeway.ioc;

public interface Container extends AutoCloseable {
    <T> T get(Class<T> type);

    <T> T get(Class<T> type, String id);

    <T> Extension<T> extension(Class<T> entryType);

    @Override
    void close();
}
