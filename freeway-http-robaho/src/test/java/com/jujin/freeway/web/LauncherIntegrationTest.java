package com.jujin.freeway.web;

import com.jujin.freeway.boot.App;
import com.jujin.freeway.boot.Launcher;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Module;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LauncherIntegrationTest {
    private App app;

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.close();
        }
        System.clearProperty("web.server.port");
        System.clearProperty("web.server.host");
        System.clearProperty("web.engine");
    }

    @Test
    void launcherDiscoversWebModuleAndStartsServerOnDemand() throws Exception {
        int port = freePort();
        System.setProperty("web.server.host", "127.0.0.1");
        System.setProperty("web.server.port", String.valueOf(port));

        app = Launcher.run(new TestAppModule());
        app.get(WebServer.class);

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/ping"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        assertEquals("pong", response.body());
    }

    public static final class TestAppModule implements Module {
        @Override
        public void bind(Binder binder) {
            binder.contribute(Route.class).add(Route.get("/ping", ctx -> ctx.send(200, "pong")));
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
