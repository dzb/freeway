package com.jujin.freeway.http.engine.ws;
import java.io.ByteArrayOutputStream;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketFrameTest {

    @Test
    void maskedFrameReportsMasked() throws Exception {
        // masked text frame "hi" with masking key [1,2,3,4]
        byte[] raw = {(byte) 0x81, (byte) 0x82, 1, 2, 3, 4,
                (byte) ('h' ^ 1), (byte) ('i' ^ 2)};
        var frame = WebSocketFrame.read(new ByteArrayInputStream(raw));
        assertTrue(frame.isMasked(), "Masked client frame should report isMasked=true");
        assertEquals("hi", frame.payloadAsString());
    }

    @Test
    void unmaskedFrameReportsNotMasked() throws Exception {
        byte[] raw = {(byte) 0x81, 0x02, 'h', 'i'};
        var frame = WebSocketFrame.read(new ByteArrayInputStream(raw));
        assertFalse(frame.isMasked(), "Unmasked frame should report isMasked=false");
    }

    @Test
    void fragmentedTextFrameIsNotFin() throws Exception {
        // Text FIN=false "hel"
        byte[] raw = {0x01, 0x03, 'h', 'e', 'l'}; // opcode=Text(1), FIN=0
        var frame = WebSocketFrame.read(new ByteArrayInputStream(raw));
        assertFalse(frame.isFin(), "Fragmented frame should have FIN=false");
        assertEquals("hel", frame.payloadAsString());
    }

    @Test
    void largeFrameWriteProducesCorrectEightByteLength() throws Exception {
        byte[] payload = new byte[70000];
        payload[0] = 'A';
        var frame = new WebSocketFrame(OpCode.Text, true, payload);
        var out = new ByteArrayOutputStream();
        frame.write(out);
        byte[] wire = out.toByteArray();
        // First byte: FIN+Text opcode
        assertEquals((byte) 0x81, wire[0]);
        // Second byte: 0x7F (127 = 64-bit length indicator)
        assertEquals((byte) 0x7F, wire[1]);
        // Next 8 bytes: big-endian 70000 = 0x0000000000011170
        assertEquals(0, wire[2]); assertEquals(0, wire[3]);
        assertEquals(0, wire[4]); assertEquals(0, wire[5]);
        assertEquals(0, wire[6]); assertEquals(1, wire[7]);
        assertEquals(0x11, wire[8] & 0xFF);
        assertEquals(0x70, wire[9] & 0xFF);
        // Verify round-trip
        var readBack = WebSocketFrame.read(new ByteArrayInputStream(wire));
        assertEquals(70000, readBack.payload().length);
        assertEquals('A', readBack.payload()[0]);
    }

    @Test
    void continuationFrameParsesPayload() throws Exception {
        // Continuation FIN=true "lo"
        byte[] raw = {(byte) 0x80, 0x02, 'l', 'o'}; // opcode=Continuation(0), FIN=1
        var frame = WebSocketFrame.read(new ByteArrayInputStream(raw));
        assertTrue(frame.isFin(), "Final continuation should have FIN=true");
        assertEquals("lo", frame.payloadAsString());
    }

    @Test
    void rejectsFrameLargerThanMaxFrameSize() throws Exception {
        // 16 MiB + 1 declared via the 64-bit length field
        byte[] header = {(byte) 0x81, (byte) 0x7F, 0, 0, 0, 0, 1, 0, 0, 1};
        WebSocketException ex = assertThrows(WebSocketException.class,
            () -> WebSocketFrame.read(new ByteArrayInputStream(header)));
        assertEquals(CloseCode.MessageTooBig, ex.closeCode());
    }
}
