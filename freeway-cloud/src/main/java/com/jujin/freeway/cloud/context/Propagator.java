package com.jujin.freeway.cloud.context;

import java.util.Map;

/**
 * Strategy for carrying one concern across a process boundary.
 *
 * <p>Contributed like {@code Route}/{@code HttpFilter} (same extension-point
 * pattern). Adding a new cross-boundary concern = contributing a
 * {@code Propagator}, never touching core: inbound {@code extract} →
 * {@code InvocationContext.runWith} (that is the only write face), outbound
 * {@code inject} of the current context.
 */
public interface Propagator {

    /** Writes the current context into outbound headers. */
    void inject(InvocationContext ctx, Map<String, String> headers);

    /**
     * Reads an inbound context from request headers, leaving absent sub-contexts unset ({@code null})
     * rather than substituting empty ones. A context that carries nothing at all is equivalent to
     * finding nothing: the propagation filter then binds no context for the request, so "the header
     * was absent" stays distinguishable from "the header was present and empty".
     */
    InvocationContext extract(Map<String, String> headers);
}
