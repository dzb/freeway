package com.jujin.freeway.cloud.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jujin.freeway.commons.json.JsonCodecDefault;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** The typed client reads per-method timeout/attempt bounds from its interface. */
class RemotePolicyTest {

    interface Api {
        @RemotePolicy(timeoutMillis = 123, maxAttempts = 2)
        int fast();

        int inherited();
    }

    @RemotePolicy(timeoutMillis = 77, maxAttempts = 1)
    interface TypedApi {
        int call();
    }

    @Test
    void methodAnnotationOverridesTheFactoryBounds() {
        RecordingClient http = new RecordingClient();
        Api api = RemoteProxyFactory.of(new RemoteCaller(http, new JsonCodecDefault()))
            .withServiceId("svc")
            .withMapping("m")
            .withTimeout(Duration.ofSeconds(5))
            .build(Api.class);

        assertEquals(1, api.fast());
        assertEquals(2, http.maxAttempts);
        assertEquals(Duration.ofMillis(123), http.deadline);
    }

    @Test
    void interfaceAnnotationAppliesWhenTheMethodHasNone() {
        RecordingClient http = new RecordingClient();
        TypedApi api = RemoteProxyFactory.of(new RemoteCaller(http, new JsonCodecDefault()))
            .withServiceId("svc")
            .withMapping("m")
            .build(TypedApi.class);

        assertEquals(1, api.call());
        assertEquals(1, http.maxAttempts);
        assertEquals(Duration.ofMillis(77), http.deadline);
    }

    @Test
    void withoutAPolicyTheFactoryBoundsRemainInCharge() {
        RecordingClient http = new RecordingClient();
        Api api = RemoteProxyFactory.of(new RemoteCaller(http, new JsonCodecDefault()))
            .withServiceId("svc")
            .withMapping("m")
            .withTimeout(Duration.ofSeconds(5))
            .build(Api.class);

        api.inherited();
        assertEquals(0, http.maxAttempts);
        assertEquals(Duration.ofSeconds(5), http.deadline);
    }

    /** Captures what the caller passes; returns a minimal JSON int reply. */
    private static final class RecordingClient implements RpcHttpClient {
        int maxAttempts = -1;
        Duration deadline;

        @Override
        public RpcResponse call(String serviceId, RpcRequest request) {
            return call(serviceId, request, 0);
        }

        @Override
        public RpcResponse call(String serviceId, RpcRequest request, int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return ok();
        }

        @Override
        public CompletableFuture<RpcResponse> callAsync(
                String serviceId, RpcRequest request, Duration deadline) {
            return callAsync(serviceId, request, deadline, 0);
        }

        @Override
        public CompletableFuture<RpcResponse> callAsync(
                String serviceId, RpcRequest request, Duration deadline, int maxAttempts) {
            this.deadline = deadline;
            this.maxAttempts = maxAttempts;
            return CompletableFuture.completedFuture(ok());
        }

        @Override
        public void close() {
        }

        private static RpcResponse ok() {
            return new RpcResponse(200, Map.of(),
                "1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
