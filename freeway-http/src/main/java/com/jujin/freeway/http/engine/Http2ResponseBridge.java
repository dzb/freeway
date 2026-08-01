package com.jujin.freeway.http.engine;

import java.util.List;
import java.util.Map;

/**
 * Bridges {@link HttpContextDefault} into HTTP/2 response framing.
 * When set on the context, header and status flows go through this
 * bridge and the body is written directly to the underlying stream.
 *
 * <p>{@link com.jujin.freeway.http.engine.http20.Http2Stream} implements
 * this interface. Tests can supply a mock implementation without
 * needing a full HTTP/2 connection.
 */
public interface Http2ResponseBridge {

    /** The mutable header map consumed by the HPACK layer on first DATA write. */
    Map<String, List<String>> headers();
}
