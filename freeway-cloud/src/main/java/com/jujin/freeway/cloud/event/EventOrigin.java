package com.jujin.freeway.cloud.event;

/**
 * "Is this identity mine" — the self-guard every transport applies before
 * accepting or forwarding an event, so a node never processes its own
 * broadcast as a peer's.
 *
 * <p>One home for three call sites that used to spell it three ways
 * (mesh fan-out, mesh inbound, Kafka consume): null-safe equality with one
 * rule — an unknown identity (null candidate) is never claimed as our own.
 * Public because transports live elsewhere (the Kafka adapter reads it from
 * another module).
 */
public final class EventOrigin {

    private EventOrigin() {}

    /**
     * @param own       this node's identity; may be null when unwired (then
     *                  nothing matches, same as before)
     * @param candidate the identity carried on the wire or connection
     * @return true only when both are non-null and equal
     */
    public static boolean isOwn(String own, String candidate) {
        return candidate != null && candidate.equals(own);
    }
}
