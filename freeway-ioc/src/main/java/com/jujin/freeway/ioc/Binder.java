package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.extension.Contributions;
import com.jujin.freeway.ioc.extension.MappedContributions;

public interface Binder {
    <T> Binding<T> bind(Class<T> type);

    <T> Contributions<T> contribute(Class<T> valueType);

    <K, V> MappedContributions<K, V> contributeMapped(Class<K> keyType, Class<V> valueType);
}
