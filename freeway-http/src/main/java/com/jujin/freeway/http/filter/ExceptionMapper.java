package com.jujin.freeway.http.filter;
import com.jujin.freeway.http.HttpContext;

@FunctionalInterface
public interface ExceptionMapper {
    boolean handle(HttpContext ctx, Exception exception) throws Exception;
}
