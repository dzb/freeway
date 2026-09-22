package com.jujin.freeway.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.http.filter.ErrorHandler;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.ModuleEx;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The built-in exception-to-response mapper is consulted LAST, whatever order the
 * contributions arrived in: an application's own mapper must be able to take a
 * case the built-in one already maps (an oversized body, a failed validation),
 * and module placement must not decide the winner.
 */
class HttpModuleErrorHandlerOrderTest {

    @Test
    void applicationMapperWinsOverTheBuiltInDefaultItIsContributedAfter() throws Exception {
        ModuleEx claimEverything = binder -> binder.contribute(ErrorHandler.class)
            .add((ctx, ex) -> {
                ctx.send(418, "application mapper");
                return true;
            });
        try (Container c = Freeway.create(new HttpModule(),
                TestHttp.config(TestServerConfig.loopback()),
                TestHttp.routes(Route.post("/boom", ctx -> {
                    ctx.setMaxBodySize(1);
                    ctx.body();
                    ctx.send(200, "unexpected");
                })),
                claimEverything)) {
            WebServer server = c.get(WebServer.class);
            server.start();
            var response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + server.port() + "/boom"))
                    .POST(HttpRequest.BodyPublishers.ofString("too large")).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals("application mapper", response.body(),
                "the application mapper must be consulted before the built-in default,"
                    + " which the module contributes first");
        }
    }
}
