package com.jujin.freeway.http.robaho;

import com.jujin.freeway.http.*;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.http.WebServer;
import com.jujin.freeway.http.body.MultipartForm;
import com.jujin.freeway.http.filter.ExceptionMapper;
import com.jujin.freeway.http.filter.HttpFilter;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.route.RouteGroup;
import com.jujin.freeway.http.staticfile.StaticResourceMount;
import com.jujin.freeway.http.staticfile.StaticResources;

class WebServerIntegrationTest {
    private Container container;
    private HttpClient client;
    private int port;

    @AfterEach
    void tearDown() {
        if (container != null) {
            container.close();
        }
        System.clearProperty("web.server.port");
        System.clearProperty("web.server.host");
    }

    @Test
    void servesRoutesFiltersAndJson() throws Exception {
        port = freePort();
        System.setProperty("web.server.host", "127.0.0.1");
        System.setProperty("web.server.port", String.valueOf(port));

        Path staticRoot = Files.createTempDirectory("freeway-static");
        Files.writeString(staticRoot.resolve("index.html"), "<html><body>home</body></html>");
        Files.writeString(staticRoot.resolve("app.js"), "console.log('ok');");

        container = Freeway.create(
            new HttpModule(),
            new RobahoWebEngineModule(),
            binder -> binder.contribute(Route.class).add(Route.get("/hello", ctx -> ctx.send(200, "hello"))),
            binder -> binder.contribute(RouteGroup.class).add(RouteGroup.of("/api",
                Route.get("/group", ctx -> ctx.send(200, "group")),
                Route.get("/items/{id}", ctx -> ctx.send(200, ctx.pathVar("id")))
            )),
            binder -> binder.contribute(Route.class).add(Route.get("/users/{id}", ctx -> ctx.send(200, ctx.pathVar("id")))),
            binder -> binder.contribute(Route.class).add(Route.get("/bind/{id}", ctx -> ctx.send(200,
                ctx.pathVar("id", Integer.class) + ":" + ctx.queryParam("page", Integer.class) + ":" + ctx.param("token")))),
            binder -> binder.contribute(Route.class).add(Route.get("/request-id", ctx -> ctx.send(200, ctx.requestContext().correlationId()))),
            binder -> binder.contribute(Route.class).add(Route.post("/echo", ctx -> ctx.sendJson(200, Map.of("body", ctx.bodyAsJson(Map.class))))),
            binder -> binder.contribute(Route.class).add(Route.post("/upload", ctx -> {
                MultipartForm form = ctx.multipart();
                MultipartForm.Part file = form.file("file").orElseThrow();
                ctx.sendJson(200, Map.of(
                    "title", form.value("title"),
                    "filename", file.filename(),
                    "size", file.size(),
                    "text", file.text()
                ));
            })),
            binder -> binder.contribute(StaticResourceMount.class).add(StaticResources.directory("/static", staticRoot).cacheMaxAgeSeconds(60)),
            binder -> binder.contribute(HttpFilter.class).add((ctx, next) -> {
                ctx.headerSet("X-Test-Filter", "on");
                next.handle(ctx);
            }),
            binder -> binder.contribute(ExceptionMapper.class).add((ctx, ex) -> {
                if (ex instanceof IllegalArgumentException) {
                    ctx.send(400, "bad request");
                    return true;
                }
                return false;
            }),
            binder -> binder.contribute(Route.class).add(Route.get("/boom", ctx -> {
                throw new IllegalArgumentException("boom");
            }))
        );
        container.get(WebServer.class).start();
        client = HttpClient.newHttpClient();

        HttpResponse<String> hello = client.send(request("/hello"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, hello.statusCode());
        assertEquals("hello", hello.body());
        assertEquals("on", hello.headers().firstValue("X-Test-Filter").orElse(""));
        assertTrue(hello.headers().firstValue("X-Request-Id").orElse("").length() >= 32);

        HttpResponse<String> path = client.send(request("/users/42"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, path.statusCode());
        assertEquals("42", path.body());

        HttpResponse<String> grouped = client.send(request("/api/group"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, grouped.statusCode());
        assertEquals("group", grouped.body());

        HttpResponse<String> groupedItem = client.send(request("/api/items/9"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, groupedItem.statusCode());
        assertEquals("9", groupedItem.body());

        HttpResponse<String> bind = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/bind/7?page=3&token=abc"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, bind.statusCode());
        assertEquals("7:3:abc", bind.body());

        HttpResponse<String> health = client.send(request("/healthz"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode());
        assertTrue(health.body().contains("\"status\":\"ok\""));

        HttpResponse<String> staticIndex = client.send(request("/static"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, staticIndex.statusCode());
        assertTrue(staticIndex.body().contains("home"));
        assertEquals("public, max-age=60", staticIndex.headers().firstValue("Cache-Control").orElse(""));
        assertTrue(staticIndex.headers().firstValue("ETag").orElse("").startsWith("\"sha256-"));

        String etag = staticIndex.headers().firstValue("ETag").orElse("");
        HttpResponse<String> notModified = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/static"))
                .header("If-None-Match", etag)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(304, notModified.statusCode());
        assertTrue(notModified.body().isEmpty());

        HttpResponse<String> staticAsset = client.send(request("/static/app.js"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, staticAsset.statusCode());
        assertTrue(staticAsset.body().contains("console.log"));
        assertTrue(staticAsset.headers().firstValue("Content-Type").orElse("").contains("javascript"));

        String requestId = UUID.randomUUID().toString().replace("-", "");
        HttpResponse<String> requestIdResponse = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/request-id"))
                .header("X-Request-Id", requestId)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, requestIdResponse.statusCode());
        assertEquals(requestId, requestIdResponse.body());
        assertEquals(requestId, requestIdResponse.headers().firstValue("X-Request-Id").orElse(""));

        HttpResponse<String> json = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/echo"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"freeway\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, json.statusCode());
        assertTrue(json.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        assertTrue(json.body().contains("\"name\":\"freeway\""));

        String boundary = "----Freeway" + UUID.randomUUID().toString().replace("-", "");
        HttpResponse<String> upload = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(boundary)))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, upload.statusCode());
        assertTrue(upload.body().contains("\"title\":\"avatar\""));
        assertTrue(upload.body().contains("\"filename\":\"hello.txt\""));
        assertTrue(upload.body().contains("\"size\":11"));
        assertTrue(upload.body().contains("\"text\":\"hello world\""));

        HttpResponse<String> boom = client.send(request("/boom"), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, boom.statusCode());
        assertEquals("bad request", boom.body());
    }

    private static HttpRequest request(String path) {
        return HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + System.getProperty("web.server.port") + path))
            .GET()
            .build();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static byte[] multipartBody(String boundary) {
        String crlf = "\r\n";
        StringBuilder body = new StringBuilder();
        body.append("--").append(boundary).append(crlf);
        body.append("Content-Disposition: form-data; name=\"title\"").append(crlf);
        body.append(crlf);
        body.append("avatar").append(crlf);
        body.append("--").append(boundary).append(crlf);
        body.append("Content-Disposition: form-data; name=\"file\"; filename=\"hello.txt\"").append(crlf);
        body.append("Content-Type: text/plain; charset=utf-8").append(crlf);
        body.append(crlf);
        body.append("hello world").append(crlf);
        body.append("--").append(boundary).append("--").append(crlf);
        return body.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
}
