package com.jujin.freeway.http;

public interface HttpServerHandle extends AutoCloseable {
    String host();

    int port();

    @Override
    void close();
}
