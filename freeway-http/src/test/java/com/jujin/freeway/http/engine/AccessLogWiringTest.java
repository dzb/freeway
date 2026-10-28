package com.jujin.freeway.http.engine;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.http.HttpServer;
import com.jujin.freeway.http.event.HttpExchangeEvent;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.event.EventSubscriber;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The module face of the access log: the enabled key writes the line from the
 * server's event sink (including probes a filter never sees), and an
 * application that wants another destination contributes its own subscriber
 * instead of replacing a filter.
 */
class AccessLogWiringTest {

    private static final String PORT_KEY = "freeway.http.server.port";
    private static final String LOG_KEY = "freeway.http.access-log.enabled";

    @Test
    void theEnabledKeyWritesTheProbeLineToo() throws Exception {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setProperty(PORT_KEY, "0");
        System.setProperty(LOG_KEY, "true");
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try (AppRuntime app = FreewayApp.create(new HttpModule())
                .autoDiscovery(false).start()) {
            int port = app.get(HttpServer.class).port();
            HttpClient.newBuilder().build().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/healthz"))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());

            await(() -> captured.toString(StandardCharsets.UTF_8)
                .contains("GET /healthz 200"));
        } finally {
            System.setOut(original);
            System.clearProperty(PORT_KEY);
            System.clearProperty(LOG_KEY);
        }

        assertTrue(captured.toString(StandardCharsets.UTF_8).contains("GET /healthz 200"),
            "the probe is answered before dispatch; the line must still exist: "
                + captured.toString(StandardCharsets.UTF_8));
    }

    @Test
    void anApplicationSubscriberReplacesTheLine() throws Exception {
        System.clearProperty(LOG_KEY);
        System.setProperty(PORT_KEY, "0");
        SubscriberModule subscriber = new SubscriberModule();
        try (AppRuntime app = FreewayApp.create(new HttpModule(), subscriber)
                .autoDiscovery(false).start()) {
            int port = app.get(HttpServer.class).port();
            HttpResponse<String> response = HttpClient.newBuilder().build().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ok"))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(404, response.statusCode(), "no route: a 404 exchange is still an exchange");
            await(() -> !subscriber.events.isEmpty());
        } finally {
            System.clearProperty(PORT_KEY);
        }

        assertFalse(subscriber.events.isEmpty(),
            "a contributed subscriber sees the exchange without the enabled key");
        assertEquals(404, subscriber.events.getFirst().statusCode());
    }

    /** One module, one contribution: the application's own destination. */
    static final class SubscriberModule implements ModuleEx {
        final List<HttpExchangeEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void bind(Binder binder) {
            binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(HttpExchangeEvent.class, events::add));
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }
}
