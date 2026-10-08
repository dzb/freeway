package com.jujin.freeway.cloud.discovery;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.http.HttpModule.ConfigKeys;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The registry server is real: it serves the SAME {@code RegistryStore} that
 * the in-process defaults wrap, so registering here makes the process's own
 * discovery side see it, and deregistering via the wire removes it.
 */
class RegistryServerModuleTest {

    @BeforeEach
    void randomPort() {
        System.setProperty(ConfigKeys.SERVER_PORT, "0");
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty(ConfigKeys.SERVER_PORT);
    }

    private static final String INSTANCE =
        "{\"serviceId\":\"svc\",\"instanceId\":\"i1\"," +
        "\"endpoint\":{\"scheme\":\"http\",\"host\":\"127.0.0.1\",\"port\":80,\"basePath\":\"\"}," +
        "\"metadata\":{}}";

    private static HttpClient client() {
        return HttpClient.newHttpClient();
    }

    private static HttpResponse<String> http(AppRuntime app, HttpRequest.Builder b)
            throws Exception {
        int port = app.get(com.jujin.freeway.http.HttpServer.class).port();
        return client().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(AppRuntime app, String path) {
        int port = app.get(com.jujin.freeway.http.HttpServer.class).port();
        return URI.create("http://127.0.0.1:" + port + path);
    }

    @Test
    void register_then_query_discovers_the_same_store() throws Exception {
        try (AppRuntime app = FreewayApp.create(
                new HttpModule(), new CloudDiscoveryModule(), new RegistryServerModule()).start()) {

            // register via the wire
            HttpResponse<String> reg = http(app, HttpRequest.newBuilder(
                    uri(app, "/registry/services/svc/instances"))
                .POST(HttpRequest.BodyPublishers.ofString(INSTANCE)));
            assertTrue(reg.statusCode() == 200, "POST /instances returned " + reg.statusCode());
            assertTrue(reg.body().contains("\"i1\""), "register echoes the instance, got: " + reg.body());

            // discover returns it
            HttpResponse<String> discover = http(app, HttpRequest.newBuilder(
                    uri(app, "/registry/services/svc/instances")).GET());
            assertTrue(discover.statusCode() == 200);
            assertTrue(discover.body().contains("\"serviceId\":\"svc\""),
                "discover must list the instance from the same store, got: " + discover.body());

            // renew is alive
            HttpResponse<String> renew = http(app, HttpRequest.newBuilder(
                    uri(app, "/registry/services/svc/instances/i1/renew"))
                .POST(HttpRequest.BodyPublishers.ofString("")));
            assertTrue(renew.statusCode() == 200, "renew returned " + renew.statusCode());

            // unregister removes it
            HttpResponse<String> del = http(app, HttpRequest.newBuilder(
                    uri(app, "/registry/services/svc/instances/i1")).DELETE());
            assertTrue(del.statusCode() == 200, "DELETE returned " + del.statusCode());

            HttpResponse<String> after = http(app, HttpRequest.newBuilder(
                    uri(app, "/registry/services/svc/instances")).GET());
            assertTrue(!after.body().contains("\"i1\""),
                "after delete the store must no longer list it, got: " + after.body());
        }
    }

    @Test
    void unknown_instance_is_a_clean_answer_not_a_refresh() throws Exception {
        try (AppRuntime app = FreewayApp.create(
                new HttpModule(), new CloudDiscoveryModule(), new RegistryServerModule()).start()) {
            HttpResponse<String> renew = http(app, HttpRequest.newBuilder(
                    uri(app, "/registry/services/svc/instances/ghost/renew"))
                .POST(HttpRequest.BodyPublishers.ofString("")));
            assertTrue(renew.statusCode() == 404, "renew must 404 for an unknown instance");
        }
    }
}
