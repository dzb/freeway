package com.jujin.freeway.cloud.events;

/**
 * Inbound pipeline interceptor (contribution): runs on every frame received
 * from a peer, before local dispatch. Implement audit, tenant checks, custom
 * filtering — or return false to drop the frame (design doc §4.2).
 *
 * <p>The built-in idempotency dedup (when enabled) is itself an interceptor.
 */
public interface CloudEventInterceptor {

    /**
     * @param frame the decoded wire frame
     * @return true to continue dispatch, false to drop the frame
     */
    boolean onInbound(CloudEventEnvelope.Parsed frame);
}
