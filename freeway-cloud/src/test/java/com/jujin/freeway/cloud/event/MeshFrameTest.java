package com.jujin.freeway.cloud.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The frame classification both mesh legs now share.
 *
 * <p>Every case here was previously written twice — once in {@code PeerHub},
 * once in {@code PeerConnector} — and held together by comments saying the two
 * copies mirrored each other. The security-relevant one is {@code
 * eventBeforeHandshake}: an event frame must never reach the broadcast plane
 * before the peer is admitted, and that rule was stated in two places and
 * enforced in two places.
 *
 * <p>Testing the shared classifier rather than each leg is the point: the bug
 * this guards against is the two copies drifting apart, and a test per leg
 * would pass whichever way each one was written.
 */
class MeshFrameTest {

    private static final String HELLO = "{\"proto\":1,\"origin\":\"a\",\"serviceId\":\"s\"}";
    private static final String EVENT =
        "{\"specversion\":\"1.0\",\"id\":\"e1\",\"type\":\"t\",\"source\":\"s\"}";

    @Test
    void helloIsAcceptedBeforeAdmission() {
        var frame = MeshFrame.classify(HELLO, false);
        var hello = assertInstanceOf(MeshFrame.Hello.class, frame);
        assertEquals(1, hello.payload().get("proto"));
    }

    @Test
    void aSecondHelloIsRefusedAfterAdmission() {
        assertInstanceOf(MeshFrame.DuplicateHello.class, MeshFrame.classify(HELLO, true));
    }

    @Test
    void eventIsDeliveredOnlyAfterAdmission() {
        var frame = MeshFrame.classify(EVENT, true);
        var event = assertInstanceOf(MeshFrame.Event.class, frame);
        assertTrue(event.text().contains("e1"),
            "the delivered text is what the receiver parses — it must be intact");
    }

    @Test
    void eventBeforeHelloIsNeverDelivered() {
        // The admission gate. A peer that has not been admitted has not run our
        // token check, so its events cannot be trusted — this is the one case
        // that must be identical on both legs, and it used to be written twice.
        assertInstanceOf(MeshFrame.EventBeforeHello.class, MeshFrame.classify(EVENT, false));
    }

    @Test
    void anUnknownFrameIsNotSilentlyIgnored() {
        // Ignoring it would let a session that is not speaking the mesh
        // protocol stay alive indefinitely. Unrecognized on either side of the
        // handshake — the frame is unknown, and admission does not change that.
        assertInstanceOf(MeshFrame.Unrecognized.class,
            MeshFrame.classify("{\"something\":\"else\"}", true));
        assertInstanceOf(MeshFrame.Unrecognized.class,
            MeshFrame.classify("{\"something\":\"else\"}", false));
        assertInstanceOf(MeshFrame.Unrecognized.class,
            MeshFrame.classify("{}", true));
    }

    @Test
    void unparseableTextIsMalformedRatherThanUnrecognized() {
        // Distinct because the legs report it differently: garbage from a peer
        // is a protocol error, not an unknown future feature.
        var frame = assertInstanceOf(MeshFrame.Malformed.class,
            MeshFrame.classify("this is not json", true));
        assertTrue(frame.reason() != null && !frame.reason().isEmpty(),
            "the reason must survive for the log line");
    }

    @Test
    void admissionIsTheOnlyThingThatChangesTheOutcome() {
        // The protocol has three frame shapes; the handshake state is what
        // decides between accepting and refusing each. Pinning that mapping as
        // a table is what makes a leg's copy of the rule checkable at a glance
        // — and it is the mapping the two copies used to disagree about.
        assertEquals(MeshFrame.Hello.class, MeshFrame.classify(HELLO, false).getClass());
        assertEquals(MeshFrame.DuplicateHello.class, MeshFrame.classify(HELLO, true).getClass());
        assertEquals(MeshFrame.EventBeforeHello.class, MeshFrame.classify(EVENT, false).getClass());
        assertEquals(MeshFrame.Event.class, MeshFrame.classify(EVENT, true).getClass());
    }
}
