package com.jujin.freeway2.web;

@FunctionalInterface
public interface HttpFilter {
    void doFilter(HttpContext ctx, RouteHandler next) throws Exception;
}
