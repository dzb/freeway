package com.jujin.freeway.http;

@FunctionalInterface
public interface HttpFilter {
    void doFilter(HttpContext ctx, RouteHandler next) throws Exception;
}
