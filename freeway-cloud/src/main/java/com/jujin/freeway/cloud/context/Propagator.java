package com.jujin.freeway.cloud.context;

import java.util.Map;

/**
 * Strategy for carrying one concern across a process boundary.
 *
 * <p>Contributed like {@code Route}/{@code HttpFilter} (same extension-point
 * pattern). Adding a new cross-boundary concern = contributing a
 * {@code Propagator}, never touching core: inbound {@code extract} →
 * {@code InvocationContext.enter}, outbound {@code inject} of the current
 * context.
 */
public interface Propagator {

    /** Writes the current context into outbound headers. */
    void inject(InvocationContext ctx, Map<String, String> headers);

    /** Reads an inbound context from request headers (empty sub-contexts where absent). */
    InvocationContext extract(Map<String, String> headers);
}
