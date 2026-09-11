package com.jujin.freeway.cloud.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.ioc.CallBus;
import com.jujin.freeway.ioc.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Builder validation and dispatch-mode semantics for {@link RemoteProxyFactory}. */
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

    public static class LocalEcho {
        public String echo(String word) { return "local:" + word; }
    }

    @Test
    void localFirstHitsLocalSlotWithoutRemote() {
        CallBus bus = new CallBus(new NoopContainer());
        bus.register("test", new LocalEcho());
        // RemoteCaller null proves the remote is never consulted when local hits.
        EchoApi api = RemoteProxyFactory.of(bus, null)
            .mapping("test")
            .localFirst()
            .build(EchoApi.class);
        assertEquals("local:hi", api.echo("hi"));
    }

    @Test
    void localFirstWithoutRemoteFailsFastOnDeadCall() {
        CallBus bus = new CallBus(new NoopContainer());
        EchoApi api = RemoteProxyFactory.of(bus, null)
            .mapping("absent")
            .localFirst()
            .build(EchoApi.class);
        // No local handler, no remote fallback: the dead call must reach the caller.
        assertThrows(Exception.class, () -> api.echo("x"));
    }

    @Test
    void localFirstWithRemoteFallbackRequiresServiceId() {
        // The remote leg only runs after a local DeadCall — but the missing
        // serviceId is knowable at build() and must not surface later as an
        // unrelated "serviceId must not be blank" transport failure.
        assertThrows(IllegalStateException.class, () ->
            RemoteProxyFactory.of(new CallBus(new NoopContainer()),
                    new RemoteCaller(null, null))
                .mapping("x")
                .localFirst()
                .build(EchoApi.class));
    }

    @Test
    void builderValidation() {
        assertThrows(IllegalArgumentException.class, () ->
            RemoteProxyFactory.of(null, null));                                // nothing given
        assertThrows(IllegalStateException.class, () ->
            RemoteProxyFactory.of(new CallBus(new NoopContainer()), null)
                .mapping("x").build(EchoApi.class));                           // no mode
        assertThrows(IllegalArgumentException.class, () ->
            RemoteProxyFactory.of(null, null).localFirst());
        assertThrows(IllegalStateException.class, () ->
            RemoteProxyFactory.of(new CallBus(new NoopContainer()), null)
                .localFirst().build(EchoApi.class));                           // no mapping
        assertThrows(IllegalArgumentException.class, () ->
            RemoteProxyFactory.of(new CallBus(new NoopContainer()), null)
                .mapping("x").localFirst().build(String.class));               // not an interface
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

        StockApi stock = RemoteProxyFactory.of(null, caller)
            .serviceId("stock").mapping("stock").remoteOnly()
            .build(StockApi.class);
        assertEquals("ok", stock.quote("ACME"));
        assertEquals("ok", stock.trade("ACME", 10));
        assertEquals(List.of(true, false),
            seen, "only the @Idempotent method may be replayed");

        seen.clear();
        CatalogApi catalog = RemoteProxyFactory.of(null, caller)
            .serviceId("catalog").mapping("catalog").remoteOnly()
            .build(CatalogApi.class);
        assertEquals("ok", catalog.find("SKU-1"));
        assertEquals(List.of(true), seen, "a type-level marker covers every method");
    }

    /** Minimal Container: CallBus construction only resolves Metrics. */
    static class NoopContainer implements Container {
        private final com.jujin.freeway.commons.metrics.Metrics metrics
            = new com.jujin.freeway.commons.metrics.NoopMetrics();
        @SuppressWarnings("unchecked")
        public <T> T get(Class<T> type) {
            if (type == com.jujin.freeway.commons.metrics.Metrics.class) return (T) metrics;
            throw new UnsupportedOperationException(String.valueOf(type));
        }
        public <T> T get(Class<T> type, String id) { return get(type); }
        @SafeVarargs public final <T> T get(Class<T> type, Class<? extends java.lang.annotation.Annotation>... markers) { return get(type); }
        @Override public <T> boolean isActiveBinding(
            Class<T> type,
            Class<? extends java.lang.annotation.Annotation>... markers) {
            throw new UnsupportedOperationException();
        }
        public <T> com.jujin.freeway.ioc.extension.Extension<T> extension(Class<T> entryType) {
            throw new UnsupportedOperationException();
        }
        public <T> T create(Class<T> type) { throw new UnsupportedOperationException(); }
        public java.util.List<com.jujin.freeway.ioc.ModuleEx> modules() { return java.util.List.of(); }
        public void close() {}
    }
}
