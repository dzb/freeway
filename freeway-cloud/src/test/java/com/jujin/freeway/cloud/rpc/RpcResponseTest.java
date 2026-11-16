package com.jujin.freeway.cloud.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RpcResponseTest {

    @Test
    void headersAreCopiedDeeplyAndExposedReadOnly() {
        List<String> values = new ArrayList<>(List.of("a"));
        Map<String, List<String>> source = new HashMap<>();
        source.put("x", values);

        RpcResponse response = new RpcResponse(200, source, new byte[0]);
        values.add("b");
        source.put("y", List.of("c"));

        assertEquals(List.of("a"), response.headers().get("x"),
            "mutating the caller's header list must not change the response");
        assertEquals(Map.of("x", List.of("a")), response.headers());
        assertThrows(UnsupportedOperationException.class,
            () -> response.headers().get("x").add("d"),
            "header value lists are immutable");
        assertThrows(UnsupportedOperationException.class,
            () -> response.headers().put("z", List.of("new")),
            "the header map is immutable");
    }
}
