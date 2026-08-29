package com.jujin.freeway.cloud.events;

/**
 * Inbound pipeline interceptor (contribution): runs on every frame received
 * from a peer, before local dispatch. Implement audit, tenant checks, custom
 * filtering — or return false to drop the frame (design doc §4.2).
 *
 * <p>Deduplication is deliberately <em>not</em> an interceptor. An
 * interceptor only ever sees mesh frames, so it would miss the same event
 * arriving over a second transport — which is the case that actually
 * produces duplicates. Dedup lives at the one funnel every transport passes
 * through: {@code EventBus.publishInboundWithId(...)} plus {@code
 * EventBus.enableInboundDeduplication(capacity)}.
 */
public interface CloudEventInterceptor {

    /**
     * @param frame the decoded wire frame
     * @return true to continue dispatch, false to drop the frame
     */
    boolean onInbound(CloudEventEnvelope.Parsed frame);
}
