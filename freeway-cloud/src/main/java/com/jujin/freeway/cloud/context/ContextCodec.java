package com.jujin.freeway.cloud.context;

import java.util.Map;

/**
 * Strategy for carrying one concern across a process boundary.
 *
 * <p>Contributed like {@code Route}/{@code HttpFilter} (same extension-point
 * pattern). Adding a new cross-boundary concern = contributing a
 * {@code ContextCodec}, never touching core: inbound {@code decode} →
 * {@code InvocationContext.runWith} (that is the only write face), outbound
 * {@code encode} of the current context.
 */
public interface ContextCodec {

    /** Encodes the current context into outbound headers. */
    void encode(InvocationContext ctx, Map<String, String> headers);

    /**
     * Decodes an inbound context from request headers, leaving absent sub-contexts unset ({@code null})
     * rather than substituting empty ones. A context that carries nothing at all is equivalent to
     * finding nothing: the ContextFilter then binds no context for the request, so "the header
     * was absent" stays distinguishable from "the header was present and empty".
     */
    InvocationContext decode(Map<String, String> headers);
}
