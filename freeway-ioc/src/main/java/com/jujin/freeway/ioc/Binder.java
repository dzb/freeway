package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.extension.Contributions;
import com.jujin.freeway.ioc.extension.MappedContributions;

public interface Binder {
    <T> Binding<T> bind(Class<T> type);

    <T> Contributions<T> contribute(Class<T> valueType);

    <V> MappedContributions<V> contributeMapped(Class<V> valueType);
}
