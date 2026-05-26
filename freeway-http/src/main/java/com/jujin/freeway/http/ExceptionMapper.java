package com.jujin.freeway.http;

@FunctionalInterface
public interface ExceptionMapper {
    boolean handle(HttpContext ctx, Exception exception) throws Exception;
}
