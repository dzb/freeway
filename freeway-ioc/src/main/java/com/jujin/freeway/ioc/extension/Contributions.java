package com.jujin.freeway.ioc.extension;

public interface Contributions<T> {
    void add(T value);

    Contribution add(String id, T value);
}
