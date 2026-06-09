package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.extension.Contributions;

public interface Binder {

    <T> Binding<T> bind(Class<T> type);

    <V> Contributions<V> contribute(Class<V> entryType);

    <V> Contributions<V> contribute(Class<V> entryType, String name);
}
