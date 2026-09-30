/**
 * The routing DSL and its compiled index — application-facing: {@code Route}
 * (method, pattern, handler), {@code RouteGroup} (shared prefix, expanded
 * with {@code PathJoiner}) and {@code RouteHandler}. A route may carry a
 * handler instance or a handler <em>class</em>; the class form is held by
 * {@code ResolvableHandler} until the container supplies an instance, so a missing
 * dependency fails when the index is built rather than on the first request.
 * {@code ResolvableHandler} is an implementation detail of that arrangement rather
 * than part of the application-facing surface — applications name their
 * handler class and never the wrapper.
 * {@code RouteIndex} is the frozen-trie lookup {@code HttpPipeline}
 * compiles, {@code HttpModule} binds from every contributed group, and
 * {@code HttpServer} consults per request — the same trie backs
 * {@code WebSocketIndex}. {@code PathPattern} holds single-template
 * matching plus the shared path utilities (split, decode, normalize,
 * traversal checks); the index deliberately keeps its own trie matcher
 * rather than the instance one.
 */
package com.jujin.freeway.http.route;
