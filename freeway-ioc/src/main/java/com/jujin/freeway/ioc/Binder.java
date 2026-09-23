package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.extension.Contribution;

public interface Binder {
    <T> Binding<T> bind(Class<T> type);

    <V> Contribution<V> contribute(Class<V> entryType);
}
