/**
 * The routing DSL and its compiled index — application-facing: {@code Route}
 * (method, pattern, handler), {@code RouteGroup} (shared prefix, expanded
 * with {@code PathJoiner}), {@code RouteHandler} and {@code LazyHandler}
 * (which resolves a handler class from the container when the index is
 * built, so missing handlers fail at startup rather than first request).
 * {@code RouteIndex} is the frozen-trie lookup {@code HttpPipeline}
 * compiles, {@code HttpModule} binds from every contributed group, and
 * {@code HttpServer} consults per request — the same trie backs
 * {@code WebSocketIndex}. {@code PathPattern} holds single-template
 * matching plus the shared path utilities (split, decode, normalize,
 * traversal checks); the index deliberately keeps its own trie matcher
 * rather than the instance one.
 */
package com.jujin.freeway.http.route;
