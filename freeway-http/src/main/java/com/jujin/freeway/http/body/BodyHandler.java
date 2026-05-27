package com.jujin.freeway.http.body;

import com.jujin.freeway.http.HttpContext;

@FunctionalInterface
public interface BodyHandler<T> {
    void handle(HttpContext ctx, T body) throws Exception;
}
