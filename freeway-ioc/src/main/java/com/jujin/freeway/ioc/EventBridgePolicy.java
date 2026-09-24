package com.jujin.freeway.ioc;

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
 *                      disables deduplication
 */
public record EventBridgePolicy(int dedupCapacity) {}
