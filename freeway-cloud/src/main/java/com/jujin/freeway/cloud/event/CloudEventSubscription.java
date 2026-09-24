package com.jujin.freeway.cloud.event;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * A declared interest in cloud events — the mesh plane's subscription unit,
 * contributed at composition time via {@code binder.contribute(
 * CloudEventSubscription.class)}.
 *
 * <p>This record is the mesh's single source of truth for interest: the
 * declared {@link #prefix()} is what the hello handshake tells peers to pull
 * (outbound filtering on their side), what the inbound gate matches frames
 * against (a frame matching no subscription is dropped, never deserialized),
 * and where delivery routes the payload. Declaring is the whole contract —
 * there is deliberately no runtime {@code subscribe}: a subscription peers
 * do not know about cannot receive anything, so opening a post-composition
 * door would create a half-working surface (received-but-filtered) with no
 * honest semantics to run.</p>
 *
 * <p>{@code type} is the deserialization target — the declared class is also
 * the allowlist entry that admits a frame's payload to be read at all (deny
 * by structure: an undeclared type can never be materialized, and no
 * reflective class loading happens for topics nobody subscribed to).</p>
 *
 * @param prefix the topic prefix this node pulls from the mesh
 * @param type   the payload type to deserialize matching frames into
 * @param handler receives each matching payload; failures are isolated
 */
public record CloudEventSubscription(
    String prefix,
    Class<?> type,
    Consumer<Object> handler
) {

    public CloudEventSubscription {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
    }

    /** Typed factory: the handler receives payloads deserialized into {@code type}. */
    public static <T> CloudEventSubscription of(String prefix, Class<T> type, Consumer<T> handler) {
        Objects.requireNonNull(handler, "handler");
        return new CloudEventSubscription(prefix, type, payload -> handler.accept(type.cast(payload)));
    }
}
