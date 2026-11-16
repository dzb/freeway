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
        RemoteCaller caller = new RemoteCaller(stubClient(), new JsonCodecDefault());
        assertThrows(NullPointerException.class, () -> RemoteProxyFactory.of(null));
        assertThrows(IllegalStateException.class, () ->
            RemoteProxyFactory.of(caller).withMapping("x").build(EchoApi.class));   // no serviceId
        assertThrows(IllegalStateException.class, () ->
            RemoteProxyFactory.of(caller).withServiceId("s").build(EchoApi.class));  // no mapping
        assertThrows(IllegalArgumentException.class, () ->
            RemoteProxyFactory.of(caller).withServiceId("s").withMapping("x")
                .build(String.class));                                          // not an interface
    }

    @Test
    void withersReturnNewFactoriesAndLeaveTheReceiverUnchanged() {
        RemoteCaller caller = new RemoteCaller(stubClient(), new JsonCodecDefault());
        RemoteProxyFactory base = RemoteProxyFactory.of(caller);
        RemoteProxyFactory withService = base.withServiceId("s");

        assertThrows(IllegalStateException.class, () -> base.build(EchoApi.class),
            "withServiceId must not mutate the base factory");
        assertThrows(IllegalStateException.class, () -> withService.build(EchoApi.class),
            "the derived factory still needs its own mapping");
    }

    @Test
    void idempotentMarkerRidesIntoTheOutgoingRequest() {
        // A recording transport: the marker's path is proxy → RemoteCaller →
        // RpcRequest, so a stub client that captures request.idempotent()
        // pins the whole chain without a server.
        List<Boolean> seen = new ArrayList<>();
        RpcHttpClient recording = new RpcHttpClient() {
            @Override
            public RpcResponse call(String serviceId, RpcRequest request, int maxAttempts) {
                return call(serviceId, request);
            }

            @Override
            public RpcResponse call(String serviceId, RpcRequest request) {
                seen.add(request.idempotent());
                return ok();
            }

            @Override
            public java.util.concurrent.CompletableFuture<RpcResponse> callAsync(
                    String serviceId, RpcRequest request) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                    call(serviceId, request));
            }

            @Override
            public java.util.concurrent.CompletableFuture<RpcResponse> callAsync(
                    String serviceId, RpcRequest request, java.time.Duration deadline) {
                return callAsync(serviceId, request);
            }

            @Override
            public java.util.concurrent.CompletableFuture<RpcResponse> callAsync(
                    String serviceId, RpcRequest request, java.time.Duration deadline,
                    int maxAttempts) {
                return callAsync(serviceId, request);
            }

            @Override
            public void close() { }
        };
        RemoteCaller caller = new RemoteCaller(recording, new JsonCodecDefault());

        StockApi stock = RemoteProxyFactory.of(caller)
            .withServiceId("stock").withMapping("stock")
            .build(StockApi.class);
        assertEquals("ok", stock.quote("ACME"));
        assertEquals("ok", stock.trade("ACME", 10));
        assertEquals(List.of(true, false),
            seen, "only the @Idempotent method may be replayed");

        seen.clear();
        CatalogApi catalog = RemoteProxyFactory.of(caller)
            .withServiceId("catalog").withMapping("catalog")
            .build(CatalogApi.class);
        assertEquals("ok", catalog.find("SKU-1"));
        assertEquals(List.of(true), seen, "a type-level marker covers every method");
    }

    /** A transport that answers {@code "ok"} for every call. */
    private static RpcHttpClient stubClient() {
        return new RpcHttpClient() {
            @Override
            public RpcResponse call(String serviceId, RpcRequest request, int maxAttempts) {
                return call(serviceId, request);
            }

            @Override
            public RpcResponse call(String serviceId, RpcRequest request) {
                return ok();
            }

            @Override
            public java.util.concurrent.CompletableFuture<RpcResponse> callAsync(
                    String serviceId, RpcRequest request) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                    call(serviceId, request));
            }

            @Override
            public java.util.concurrent.CompletableFuture<RpcResponse> callAsync(
                    String serviceId, RpcRequest request, java.time.Duration deadline) {
                return callAsync(serviceId, request);
            }

            @Override
            public java.util.concurrent.CompletableFuture<RpcResponse> callAsync(
                    String serviceId, RpcRequest request, java.time.Duration deadline,
                    int maxAttempts) {
                return callAsync(serviceId, request);
            }

            @Override
            public void close() { }
        };
    }

    private static RpcResponse ok() {
        return new RpcResponse(200, Map.of(),
            "\"ok\"".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void objectMethodsAreAnsweredLocally() {
        RemoteCaller caller = new RemoteCaller(stubClient(), new JsonCodecDefault());
        EchoApi api = RemoteProxyFactory.of(caller)
            .withServiceId("s").withMapping("m").build(EchoApi.class);

        // No transport is reached: identity semantics stay Java's.
        assertEquals(api, api);
        assertEquals(api.hashCode(), api.hashCode());
        assertEquals("RemoteProxyFactory{serviceId=s, mapping=m}", api.toString());
    }
}
