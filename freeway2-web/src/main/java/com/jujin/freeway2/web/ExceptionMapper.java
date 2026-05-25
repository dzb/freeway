package com.jujin.freeway2.web;

@FunctionalInterface
public interface ExceptionMapper {
    boolean handle(HttpContext ctx, Exception exception) throws Exception;
}
