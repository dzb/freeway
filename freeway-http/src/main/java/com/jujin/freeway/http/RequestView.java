package com.jujin.freeway.http;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shared read-only view of request identification and parameters, common to
 * HTTP exchanges ({@link HttpRequest}) and upgraded WebSocket sessions
 * ({@link com.jujin.freeway.http.websocket.WebSocketSession}).
 *
 * <p>Single-value accessors return {@link Optional}; the multi-value and
 * whole-map accessors return lists or immutable snapshots. Header names are
 * case-insensitive.</p>
 */
public interface RequestView {

    /** Returns the HTTP method (GET, POST, etc.). */
    String method();

    /** Returns the request path. */
    String path();

    /** Returns the first query parameter value for the given name. */
    Optional<String> queryParam(String name);

    /** Returns all query parameter values for the given name. */
    List<String> queryParams(String name);

    /** Returns an unmodifiable map of all query parameters. */
    Map<String, List<String>> queryParams();

    /** Returns the first request header value for the given name. */
    Optional<String> header(String name);

    /** Returns all request header values for the given name. */
    List<String> headers(String name);

    /** Returns an unmodifiable map of all request headers. */
    Map<String, List<String>> headers();

    /** Returns a path parameter value by name. */
    Optional<String> pathVar(String name);

    /** Returns an unmodifiable map of all path parameter values. */
    Map<String, String> pathVars();
}
