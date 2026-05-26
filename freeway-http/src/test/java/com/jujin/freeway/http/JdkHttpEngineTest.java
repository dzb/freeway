package com.jujin.freeway.http;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdkHttpEngineTest {

    @Test
    void servesRoutesViaJdkEngine() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
        System.setProperty("web.server.host", "127.0.0.1");
        System.setProperty("web.server.port", String.valueOf(port));
        System.setProperty("web.engine", "jdk");

        Container c = Freeway.create(
            new HttpModule(),
            binder -> binder.contribute(Route.class).add(Route.get("/ping", ctx -> ctx.send(200, "pong")))
        );
        c.get(WebServer.class);

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> r = client.send(
            HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/ping")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, r.statusCode());
        assertEquals("pong", r.body());
        c.close();
    }
}
