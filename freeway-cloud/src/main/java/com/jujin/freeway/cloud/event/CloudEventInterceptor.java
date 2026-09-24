package com.jujin.freeway.cloud.event;

/**
 * Inbound pipeline interceptor (contribution): runs on every frame received
 * from a peer, before local dispatch. Implement audit, tenant checks, custom
 * filtering — or return false to drop the frame (design doc §4.2).
 *
 * <p>Deduplication is gone from the plane, and this is why there is nothing
 * to deduplicate: a mesh frame is delivered once to the declared
 * subscriptions (at-most-once fabric, no redelivery), and an event has no
 * second transport to copy it across — the cross-plane copies whose
 * correlation needed the window were a property of the retired
 * bus-to-mesh bridge.</p>
 */
public interface CloudEventInterceptor {

    /**
     * @param frame the decoded wire frame
     * @return true to continue dispatch, false to drop the frame
     */
    boolean onInbound(CloudEventEnvelope.Parsed frame);
}
