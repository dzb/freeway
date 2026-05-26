package com.jujin.freeway.http;

@FunctionalInterface
public interface RouteHandler {
    void handle(HttpContext ctx) throws Exception;
}
