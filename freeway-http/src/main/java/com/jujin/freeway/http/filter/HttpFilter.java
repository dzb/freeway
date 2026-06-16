package com.jujin.freeway.http.filter;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.route.RouteHandler;

@FunctionalInterface
public interface HttpFilter {
    void doFilter(HttpContext ctx, RouteHandler next) throws Exception;
}
