package com.jujin.freeway.http;

import java.util.List;
import java.util.Map;

/**
 * The request facts every layer owns before routing: method, path, query
 * parameters and headers.
 *
 * <p>{@link RequestView} adds path variables and the single-value helpers used
 * by handlers; {@link HttpTransport} implements this same head for the engine
 * boundary. Splitting the head out keeps a transport from having to pretend it
 * has path variables it cannot know, and keeps the four request facts declared
 * exactly once.</p>
 */
public interface RequestHead {

    /** Returns the HTTP method (GET, POST, etc.). */
    String method();

    /** Returns the request path. */
    String path();

    /** Returns an unmodifiable map of all query parameters. */
    Map<String, List<String>> queryParams();

    /** Returns an unmodifiable request-header map keyed by lowercase name. */
    Map<String, List<String>> headers();
}
