package com.jujin.freeway2.web;

public interface WebServerHandle extends AutoCloseable {
    String host();

    int port();

    @Override
    void close();
}
