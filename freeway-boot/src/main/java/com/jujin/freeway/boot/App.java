package com.jujin.freeway.boot;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ServiceId;

public interface App extends AutoCloseable {
    Container container();

    AppConfig config();

    default <T> T get(Class<T> type) {
        return container().get(type);
    }

    default <T> T get(Class<T> type, ServiceId id) {
        return container().get(type, id);
    }

    @Override
    void close();
}
