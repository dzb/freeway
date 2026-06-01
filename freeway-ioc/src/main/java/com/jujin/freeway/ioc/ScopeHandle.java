package com.jujin.freeway.ioc;

public interface ScopeHandle extends AutoCloseable {
    @Override
    void close();
}
