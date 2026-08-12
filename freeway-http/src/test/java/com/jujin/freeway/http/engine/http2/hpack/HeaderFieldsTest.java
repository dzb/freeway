package com.jujin.freeway.http.engine.http2.hpack;

import com.jujin.freeway.http.engine.http2.util.Http2HeaderField;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HeaderFieldsTest {
    @Test
    void acceptsStandardConnectPseudoHeaders() throws Exception {
        var fields = new HeaderFields();
        fields.add(new Http2HeaderField(":method", "CONNECT"));
        fields.add(new Http2HeaderField(":authority", "example.test:443"));
        assertDoesNotThrow(fields::validate);
    }

    @Test
    void acceptsExtendedConnectPseudoHeaders() throws Exception {
        var fields = new HeaderFields();
        fields.add(new Http2HeaderField(":method", "CONNECT"));
        fields.add(new Http2HeaderField(":authority", "example.test"));
        fields.add(new Http2HeaderField(":scheme", "https"));
        fields.add(new Http2HeaderField(":path", "/"));
        fields.add(new Http2HeaderField(":protocol", "websocket"));
        assertDoesNotThrow(fields::validate);
    }

    @Test
    void rejectsPathOnStandardConnect() throws Exception {
        var fields = new HeaderFields();
        fields.add(new Http2HeaderField(":method", "CONNECT"));
        fields.add(new Http2HeaderField(":authority", "example.test:443"));
        fields.add(new Http2HeaderField(":path", "/"));
        assertThrows(Exception.class, fields::validate);
    }
}
