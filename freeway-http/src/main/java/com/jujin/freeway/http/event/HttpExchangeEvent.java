package com.jujin.freeway.http.event;

/**
 * Published on the EventBus after each HTTP exchange completes — including
 * error responses and exchanges a built-in filter answered without reaching
 * the route (CORS preflight, health probes), because the server publishes
 * outside the filter chain.
 *
 * @param method        request method
 * @param path          request path
 * @param statusCode    response status
 * @param elapsedMs     wall time from the exchange's start to completion
 * @param remoteAddress peer address, or {@code ""} when the transport has none
 * @param userAgent     first {@code User-Agent} header, or {@code ""} when absent
 */
public record HttpExchangeEvent(String method, String path, int statusCode,
        long elapsedMs, String remoteAddress, String userAgent) {
}
