package com.jujin.freeway.ioc;

public interface Container extends AutoCloseable {
    <T> T get(Class<T> type);

    <T> T get(Class<T> type, ServiceId id);

    @Override
    void close();
}
