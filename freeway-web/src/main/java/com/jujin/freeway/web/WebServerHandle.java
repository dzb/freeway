package com.jujin.freeway.web;

public interface WebServerHandle extends AutoCloseable {
    String host();

    int port();

    @Override
    void close();
}
