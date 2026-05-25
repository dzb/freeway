package com.jujin.freeway2.web;

@FunctionalInterface
public interface RouteHandler {
    void handle(HttpContext ctx) throws Exception;
}
