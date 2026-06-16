package com.jujin.freeway.http.route;
import com.jujin.freeway.http.HttpContext;

@FunctionalInterface
public interface RouteHandler {
    void handle(HttpContext ctx) throws Exception;
}
