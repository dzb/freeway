package com.jujin.freeway.http;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.Scope;
import com.jujin.freeway.ioc.annotation.PreDestroy;
import com.jujin.freeway.http.route.Route;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The opt-in request boundary: with {@code request-scope.enabled}, a route can
 * resolve a {@code Scope.THREAD} service through the running application, the
 * same instance is reused for the request, and it is destroyed when the request
 * completes.
 */
class HttpRequestScopeTest {

    private static final AtomicInteger destroyed = new AtomicInteger();

    static final class ScopedValue {
        @PreDestroy
        void destroy() {
            destroyed.incrementAndGet();
        }
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty(HttpModule.ConfigKeys.SERVER_PORT);
        System.clearProperty(HttpModule.ConfigKeys.REQUEST_SCOPE_ENABLED);
    }

    @Test
    void requestScopeMakesThreadServicesResolvableForOneRequest() throws Exception {
        destroyed.set(0);
        System.setProperty(HttpModule.ConfigKeys.SERVER_PORT, "0");
        System.setProperty(HttpModule.ConfigKeys.REQUEST_SCOPE_ENABLED, "true");

        AtomicReference<AppRuntime> runtime = new AtomicReference<>();
        ModuleEx module = new ModuleEx() {
            @Override
            public void bind(Binder binder) {
                binder.bind(ScopedValue.class).to(ScopedValue.class).scope(Scope.THREAD);
                binder.contribute(Route.class).add(Route.get("/scope", ctx -> {
                    ScopedValue first = runtime.get().get(ScopedValue.class);
                    ScopedValue second = runtime.get().get(ScopedValue.class);
                    ctx.send(200, first == second ? "same" : "different");
                }));
            }
        };

        try (AppRuntime app = FreewayApp.run(new HttpModule(), module)) {
            runtime.set(app);
            int port = app.get(HttpServer.class).port();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/scope"))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals("same", response.body(),
                "one THREAD-scoped instance per request boundary");
            assertEquals(1, destroyed.get(),
                "the request boundary destroys the scoped instance before the response returns");
        }
    }
}
