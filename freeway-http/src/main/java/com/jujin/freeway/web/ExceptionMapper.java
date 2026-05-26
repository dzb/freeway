package com.jujin.freeway.web;

@FunctionalInterface
public interface ExceptionMapper {
    boolean handle(HttpContext ctx, Exception exception) throws Exception;
}
