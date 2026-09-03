package com.jujin.freeway.cloud.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Fragment reassembly on the client leg of the mesh: a peer's server fragments
 * anything above its frame cap, so the CONTINUATION sequence must arrive as one
 * message — and must never grow without bound.
 */
class TextMessageAssemblerTest {

    @Test
    void singleFramePassesThrough() {
        var assembler = new TextMessageAssembler(1024);
        assertEquals("{\"a\":1}", assembler.accept("{\"a\":1}", true));
    }

    @Test
    void fragmentsStitchIntoTheOriginalMessage() {
        var assembler = new TextMessageAssembler(1024);
        assertNull(assembler.accept("{\"spec", false), "no message before FIN");
        assertNull(assembler.accept("version\":\"1", false), "still mid-message");
        assertEquals("{\"specversion\":\"1\"}", assembler.accept("\"}", true));
    }

    @Test
    void limitAppliesPerMessageNotPerConnection() {
        var assembler = new TextMessageAssembler(8);
        assembler.accept("1234", false);
        assertEquals("12345678", assembler.accept("5678", true));
        // A fresh message starts clean, and the limit applies to it alone.
        assertThrows(IllegalStateException.class, () -> assembler.accept("123456789", true));
    }

    @Test
    void overflowReleasesTheBufferAndRejectsTheMessage() {
        var assembler = new TextMessageAssembler(10);
        assembler.accept("123456", false);
        assertThrows(IllegalStateException.class, () -> assembler.accept("789012", false),
            "a peer that never sets FIN must not pin memory");
        // The rejected message is gone, not still accumulating.
        assertEquals("ok", assembler.accept("ok", true));
    }
}
