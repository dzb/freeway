package com.jujin.freeway.web;

@FunctionalInterface
public interface RouteHandler {
    void handle(HttpContext ctx) throws Exception;
}
