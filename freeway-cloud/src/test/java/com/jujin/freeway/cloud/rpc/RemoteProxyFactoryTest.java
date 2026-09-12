package com.jujin.freeway.cloud.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jujin.freeway.commons.json.JsonCodecDefault;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Declaration validation and dispatch semantics for {@link RemoteProxyFactory}. */
class RemoteProxyFactoryTest {

    interface EchoApi {
        String echo(String word);
    }

    /** Marked per method: only quote() may be replayed on ambiguous outcomes. */
    interface StockApi {
        @Idempotent String quote(String symbol);
        String trade(String symbol, int amount);
    }

    /** Marked on the interface: every operation may be replayed. */
    @Idempotent
    interface CatalogApi {
        String find(String sku);
    }

    @Test
    void declarationIsValidatedUpFront() {
        RemoteCaller caller = new RemoteCaller(null, null);
        assertThrows(NullPointerException.class, () -> RemoteProxyFactory.of(null));
        assertThrows(IllegalStateException.class, () ->
            RemoteProxyFactory.of(caller).mapping("x").build(EchoApi.class));   // no serviceId
        assertThrows(IllegalStateException.class, () ->
            RemoteProxyFactory.of(caller).serviceId("s").build(EchoApi.class));  // no mapping
        assertThrows(IllegalArgumentException.class, () ->
            RemoteProxyFactory.of(caller).serviceId("s").mapping("x")
                .build(String.class));                                          // not an interface
    }

    @Test
    void idempotentMarkerRidesIntoTheOutgoingRequest() {
        // A recording transport: the marker's path is proxy → RemoteCaller →
        // CloudRequest, so a stub client that captures request.idempotent()
        // pins the whole chain without a server.
        List<Boolean> seen = new ArrayList<>();
        CloudHttpClient recording = (serviceId, request) -> {
            seen.add(request.idempotent());
            return new CloudResponse(200, Map.of(),
                "\"ok\"".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        };
        RemoteCaller caller = new RemoteCaller(recording, new JsonCodecDefault());

        StockApi stock = RemoteProxyFactory.of(caller)
            .serviceId("stock").mapping("stock")
            .build(StockApi.class);
        assertEquals("ok", stock.quote("ACME"));
        assertEquals("ok", stock.trade("ACME", 10));
        assertEquals(List.of(true, false),
            seen, "only the @Idempotent method may be replayed");

        seen.clear();
        CatalogApi catalog = RemoteProxyFactory.of(caller)
            .serviceId("catalog").mapping("catalog")
            .build(CatalogApi.class);
        assertEquals("ok", catalog.find("SKU-1"));
        assertEquals(List.of(true), seen, "a type-level marker covers every method");
    }

    @Test
    void objectMethodsAreAnsweredLocally() {
        RemoteCaller caller = new RemoteCaller(null, null);
        EchoApi api = RemoteProxyFactory.of(caller)
            .serviceId("s").mapping("m").build(EchoApi.class);

        // No transport is reached: identity semantics stay Java's.
        assertEquals(api, api);
        assertEquals(api.hashCode(), api.hashCode());
        assertEquals("RemoteProxyFactory{serviceId=s, mapping=m}", api.toString());
    }
}
