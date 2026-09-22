package com.jujin.freeway.http.engine;

import com.jujin.freeway.http.TestServerConfig;

import com.jujin.freeway.commons.validation.NotBlank;
import com.jujin.freeway.commons.validation.NotNull;
import com.jujin.freeway.commons.validation.Size;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.WebServer;
import com.jujin.freeway.http.RequestComponents;
import com.jujin.freeway.http.TestHttp;
import com.jujin.freeway.http.route.Route;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage for the request API an application writes: the typed
 * accessors on {@code HttpRequest} ({@code queryParam/header/pathVar/param}
 * with a {@code Class}) and the typed-body route factories
 * ({@code post/put/patch(path, bodyType, handler)} plus the verb factories
 * {@code head}/{@code options}).
 *
 * <p>These are framework surface: the repository has no caller for most of
 * them because the applications that use them live outside it. Unpinned public
 * API is API without a contract, so each family is exercised here through a
 * real server and a real client.
 */
class TypedRequestApiTest {

    /** Body type for the typed-body factories; the constraints drive the 400 path. */
    public record NewUser(@NotBlank String name, @NotNull @Size(min = 6, max = 64) String password) {}

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static WebServer server(int port, Route... routes) {
        return WebServer.create(TestHttp.engine(),
            TestServerConfig.loopback(port), RequestComponents.of(routes));
    }

    private static HttpResponse<String> send(int port, HttpRequest request) throws Exception {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void typedRequestAccessorsParseThroughTheContainerCoercer() throws Exception {
        int port = freePort();
        var server = server(port,
            Route.get("/items/{id}", ctx -> ctx.send(200,
                // pathVar, queryParam and header, each parsed to a target type
                ctx.pathVar("id", Long.class).orElse(-1L)
                    + ":" + ctx.queryParam("limit", Integer.class).orElse(-1)
                    + ":" + ctx.header("X-Retry", Boolean.class).orElse(false))),
            Route.get("/plain", ctx -> ctx.send(200,
                "missing=" + ctx.queryParam("nope", Integer.class).isEmpty())));
        server.start();
        try {
            var typed = send(port, HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/items/42?limit=7"))
                .header("X-Retry", "true")
                .build());
            assertEquals(200, typed.statusCode());
            assertEquals("42:7:true", typed.body(),
                "pathVar/queryParam/header must coerce to the requested type");

            var missing = send(port, HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/plain")).build());
            assertEquals("missing=true", missing.body(),
                "an absent value is empty, not a coercion failure");
        } finally {
            server.stop();
        }
    }

    @Test
    void invalidTypedValueFailsTheRequestInsteadOfPassingGarbage() throws Exception {
        int port = freePort();
        var server = server(port,
            Route.get("/n", ctx ->
                ctx.send(200, String.valueOf(ctx.queryParam("n", Integer.class).orElse(0)))));
        server.start();
        try {
            var response = send(port, HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/n?n=abc")).build());
            assertTrue(response.statusCode() >= 400,
                "an unparseable value must surface as an error, got " + response.statusCode());
        } finally {
            server.stop();
        }
    }

    @Test
    void typedBodyFactoriesDeserializeAndValidate() throws Exception {
        int port = freePort();
        var server = server(port,
            Route.post("/users", NewUser.class,
                (ctx, user) -> ctx.send(201, "post:" + user.name())),
            Route.put("/users", NewUser.class,
                (ctx, user) -> ctx.send(200, "put:" + user.name())),
            Route.patch("/users", NewUser.class,
                (ctx, user) -> ctx.send(200, "patch:" + user.name())));
        server.start();
        try {
            String body = "{\"name\":\"ada\",\"password\":\"secret1\"}";
            for (String verb : new String[] {"POST", "PUT", "PATCH"}) {
                var response = send(port, HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/users"))
                    .header("Content-Type", "application/json")
                    .method(verb, HttpRequest.BodyPublishers.ofString(body))
                    .build());
                assertEquals(verb.equals("POST") ? 201 : 200, response.statusCode());
                assertEquals(verb.toLowerCase() + ":ada", response.body(),
                    verb + " must hand the handler a deserialized body");
            }

            // The validation branch of the typed-body wrapper: a body that breaks
            // @NotBlank/@Size must be rejected, not handed to the handler.
            var invalid = send(port, HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/users"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"\",\"password\":\"x\"}"))
                .build());
            assertEquals(400, invalid.statusCode(),
                "a bean-validation failure is a client error, got " + invalid.body());
        } finally {
            server.stop();
        }
    }

    @Test
    void headAndOptionsFactoriesAnswerTheirVerbs() throws Exception {
        int port = freePort();
        var server = server(port,
            Route.head("/thing", ctx -> ctx.send(200, "ignored")),
            Route.options("/thing", ctx -> ctx.setHeader("Allow", "GET, OPTIONS").send(204, "")));
        server.start();
        try {
            var head = send(port, HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/thing"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build());
            assertEquals(200, head.statusCode());
            assertEquals("", head.body(), "HEAD carries no body");

            var options = send(port, HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/thing"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build());
            assertEquals(204, options.statusCode());
            assertEquals("GET, OPTIONS", options.headers().firstValue("Allow").orElse(""));
        } finally {
            server.stop();
        }
    }
}
