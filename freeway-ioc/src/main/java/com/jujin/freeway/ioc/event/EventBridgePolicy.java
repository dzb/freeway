package com.jujin.freeway.ioc.event;

/**
 * The bridge's policy, contributed at composition time — today only the
 * inbound-dedup window capacity.
 *
 * <p>Dedup is armed by contributing a policy, not by calling the bus: the
 * capacity is configuration (cloud reads it from its own keys during the
 * contribution drain), and the bus stays a local face with no policy knobs.
 * Absent means off; a non-positive capacity means off; two policies is a
 * composition error and fails loudly when the bus is built.
 *
 * @param dedupCapacity bound on remembered inbound wire ids; zero or negative
 *                      disables deduplication. Size it as "how far back two
 *                      copies of the same event may be spread": too small a
 *                      window lets a slow second copy through, too large one
 *                      costs memory for nothing. Ids are claimed at dispatch
 *                      time (inside the deferred action when a {@code Defer}
 *                      scope buffers the publish), so a rollback leaves the id
 *                      unclaimed and the broker's redelivery is accepted.
 */
public record EventBridgePolicy(int dedupCapacity) {}
