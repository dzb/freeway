package com.jujin.freeway.http;

import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.filter.ExceptionMapper;
import com.jujin.freeway.http.filter.HealthFilter;
import com.jujin.freeway.http.filter.HttpFilter;
import com.jujin.freeway.http.route.RouteIndex;
import com.jujin.freeway.http.staticfile.StaticResourceMount;
import com.jujin.freeway.http.websocket.WebSocketIndex;
import java.util.List;

/**
 * Bundles the request-handling components that form a WebServer's pipeline.
 */
public record RequestPipeline(
    RouteIndex routes,
    WebSocketIndex websocketIndex,
    CorsFilter corsFilter,
    HealthFilter healthFilter,
    List<StaticResourceMount> staticMounts,
    List<HttpFilter> filters,
    List<ExceptionMapper> mappers
) {}