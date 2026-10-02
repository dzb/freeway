package com.jujin.freeway.cloud.event;

import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;

/**
 * Classifies one inbound mesh frame — the part of the wire protocol that both
 * legs must agree on.
 *
 * <p>The dialer and the hub each had their own copy of the same five-way
 * dispatch: hello, hello-again, event, event-before-hello, unrecognized. The
 * copies were held together only by comments saying "mirror the server leg",
 * which is exactly the arrangement where a fix to one is quietly not applied
 * to the other. The security property in particular — an event frame must
 * never reach the broadcast plane before the peer is admitted — was stated
 * twice and enforced in two places.
 *
 * <p>What is left to each leg is only what genuinely differs: what to send
 * back, and whether a bad frame closes the session or aborts the dial.
 * Everything that decides <em>what a frame is</em> lives here.
 */
sealed interface MeshFrame {

    /** A hello: the first frame of a session, one-shot. */
    record Hello(JsonObject payload) implements MeshFrame {}

    /** A hello arriving on an already-admitted session. */
    record DuplicateHello() implements MeshFrame {}

    /** A CloudEvent from a peer. */
    record Event(String text) implements MeshFrame {}

    /**
     * An event arriving before the handshake. Never delivered: the receiving
     * peer has not run its admission, so the frame cannot be trusted.
     */
    record EventBeforeHello() implements MeshFrame {}

    /** Anything else — a frame this protocol version does not define. */
    record Unrecognized() implements MeshFrame {}

    /**
     * A frame that is not parseable as a mesh frame at all. Distinct from
     * {@link Unrecognized} because the legs report it differently: a peer
     * sending garbage is a protocol error, not an unknown future feature.
     */
    record Malformed(String reason) implements MeshFrame {}

    /**
     * The one shared rule: which field marks a frame, and whether the session
     * is allowed to see it yet.
     *
     * @param handshaken whether this session has been admitted
     */
    static MeshFrame classify(String text, boolean handshaken) {
        JsonObject frame;
        try {
            frame = JsonUtils.parseObject(text);
        } catch (RuntimeException e) {
            return new Malformed(e.getMessage() == null ? e.toString() : e.getMessage());
        }
        // specversion first: it is the one field CloudEvents reserves and every
        // conforming producer sets, while "proto" is an extension attribute a
        // third-party producer is free to use. Testing "proto" first meant such
        // an event was classified as a hello, and a hello on an admitted session
        // is a protocol error — so a valid event could be refused by the very
        // rule this class exists to state once.
        if (frame.containsKey("specversion")) {
            return handshaken ? new Event(text) : new EventBeforeHello();
        }
        if (frame.containsKey("proto")) {
            return handshaken ? new DuplicateHello() : new Hello(frame);
        }
        return new Unrecognized();
    }
}
