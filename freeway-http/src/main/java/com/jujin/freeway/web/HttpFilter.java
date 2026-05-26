package com.jujin.freeway.web;

@FunctionalInterface
public interface HttpFilter {
    void doFilter(HttpContext ctx, RouteHandler next) throws Exception;
}
