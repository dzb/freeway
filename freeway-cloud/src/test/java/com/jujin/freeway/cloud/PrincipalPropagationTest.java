package com.jujin.freeway.cloud;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.context.PrincipalContext;
import com.jujin.freeway.cloud.discovery.Endpoint;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.cloud.internal.AuthPropagator;
import com.jujin.freeway.cloud.rpc.CloudHttpClient;
import com.jujin.freeway.cloud.rpc.CloudRequest;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.http.WebServer;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.route.RouteHandler;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verified-identity propagation end-to-end: an inbound {@code x-principal}
 * header binds the principal for the request scope, and the outbound
 * CloudHttpClient call re-injects it so the callee sees the same identity.
 */
class PrincipalPropagationTest {

    @BeforeEach
    void randomPort() {
        System.setProperty(HttpConfigKeys.SERVER_PORT, "0");
        System.setProperty(CloudConfigKeys.AUTH_EXTRACT_ENABLED, "true");
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty(HttpConfigKeys.SERVER_PORT);
        System.clearProperty(CloudConfigKeys.AUTH_EXTRACT_ENABLED);
    }

    @Test
    void principalPropagatesAcrossTheCall() throws Exception {
        try (AppRuntime app = FreewayApp.run(
            new IdentityModule(), new HttpModule(), new CloudModule())) {
            WebServer server = app.get(WebServer.class);
            app.get(ServiceRegistry.class).register(
                ServiceInstance.of("svc", "i1", Endpoint.of("http", server.host(), server.port()), Map.of()));

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.port() + "/api/call"))
                .header(AuthPropagator.HEADER_PRINCIPAL, "alice")
                .header(AuthPropagator.HEADER_ROLES, "admin,user")
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());

            assertEquals("alice", IdentityModule.ENTRY_PRINCIPAL.get().name());
            assertEquals("alice", IdentityModule.CALLEE_PRINCIPAL.get().name(),
                "the outbound call re-injects the verified identity");
            assertTrue(IdentityModule.CALLEE_PRINCIPAL.get().hasRole("admin"));
        }
    }

    @Test
    void rolesContainingCommasSurviveTheWire() throws Exception {
        // Regression: roles were joined with a bare ",", so a role like
        // "admin,root" arrived as two roles on the receiving side — silent
        // permission drift. Roles share the baggage percent codec now.
        try (AppRuntime app = FreewayApp.run(
            new IdentityModule(), new HttpModule(), new CloudModule())) {
            WebServer server = app.get(WebServer.class);
            app.get(ServiceRegistry.class).register(
                ServiceInstance.of("svc", "i1", Endpoint.of("http", server.host(), server.port()), Map.of()));

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.port() + "/api/call"))
                .header(AuthPropagator.HEADER_PRINCIPAL, "alice")
                .header(AuthPropagator.HEADER_ROLES, "admin%2Croot,dev")
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());

            PrincipalContext entry = IdentityModule.ENTRY_PRINCIPAL.get();
            assertTrue(entry.hasRole("admin,root"),
                "an encoded comma is role data, not a separator");
            assertTrue(entry.hasRole("dev"));
            assertTrue(IdentityModule.CALLEE_PRINCIPAL.get().hasRole("admin,root"),
                "the callee sees the same roles after re-injection");
        }
    }

    static class IdentityModule implements ModuleEx {
        static final AtomicReference<PrincipalContext> ENTRY_PRINCIPAL = new AtomicReference<>();
        static final AtomicReference<PrincipalContext> CALLEE_PRINCIPAL = new AtomicReference<>();

        @Override
        public void bind(Binder b) {
            b.contribute(Route.class).add(Route.get("/api/call", CallHandler.class));
            b.contribute(Route.class).add(Route.get("/api/echo", ctx -> {
                CALLEE_PRINCIPAL.set(InvocationContext.current()
                    .map(InvocationContext::principal).orElse(null));
                ctx.send(200, "ok");
            }));
        }

        static class CallHandler implements RouteHandler {
            private final CloudHttpClient client;

            CallHandler(CloudHttpClient client) {
                this.client = client;
            }

            @Override
            public void handle(HttpContext ctx) throws Exception {
                ENTRY_PRINCIPAL.set(InvocationContext.current()
                    .map(InvocationContext::principal).orElse(null));
                client.call("svc", CloudRequest.get("/api/echo"));
                ctx.send(200, "ok");
            }
        }
    }
}
