package com.jujin.freeway.cloud.discovery;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.CloudModule;
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

    @Test
    void a_starting_instance_can_still_be_deregistered() throws Exception {
        // Regression: the delete path used to resolve the instance through the
        // discovery view, which hides not-ready instances — so a process that
        // failed while warming could not remove its own entry and it lingered
        // until eviction.
        try (AppRuntime app = FreewayApp.create(
                new HttpModule(), new CloudDiscoveryModule(), new RegistryServerModule()).start()) {
            http(app, HttpRequest.newBuilder(uri(app, "/registry/services/svc/instances"))
                .POST(HttpRequest.BodyPublishers.ofString(INSTANCE)));

            HttpResponse<String> warming = http(app, HttpRequest.newBuilder(
                    uri(app, "/registry/services/svc/instances/i1/ready?ready=false"))
                .POST(HttpRequest.BodyPublishers.ofString("")));
            assertTrue(warming.statusCode() == 200, "the instance can be marked warming");

            HttpResponse<String> del = http(app, HttpRequest.newBuilder(
                    uri(app, "/registry/services/svc/instances/i1")).DELETE());
            assertTrue(del.statusCode() == 200,
                "a not-ready instance must still be removable, got " + del.statusCode());
        }
    }

    @Test
    void a_registration_can_be_warming_from_its_first_write() throws Exception {
        // Readiness as a registration input: the client that knows it is
        // warming registers as starting in one write, instead of registering
        // as ready and being marked not-ready a moment later — a window in
        // which discovery could route traffic to it.
        try (AppRuntime app = FreewayApp.create(
                new HttpModule(), new CloudDiscoveryModule(), new RegistryServerModule()).start()) {
            http(app, HttpRequest.newBuilder(
                    uri(app, "/registry/services/svc/instances?ready=false"))
                .POST(HttpRequest.BodyPublishers.ofString(INSTANCE)));

            HttpResponse<String> found = http(app, HttpRequest.newBuilder(
                uri(app, "/registry/services/svc/instances")).GET());
            assertTrue(found.body().equals("[]") || found.body().isEmpty(),
                "a warming registration must not be discoverable, got " + found.body());

            http(app, HttpRequest.newBuilder(
                    uri(app, "/registry/services/svc/instances/i1/ready?ready=true")).POST(
                HttpRequest.BodyPublishers.ofString("")));
            HttpResponse<String> after = http(app, HttpRequest.newBuilder(
                uri(app, "/registry/services/svc/instances")).GET());
            assertTrue(after.body().contains("i1"),
                "and becomes discoverable when it flips ready: " + after.body());
        }
    }

    @Test
    void the_index_describes_the_protocol_before_any_credential() throws Exception {
        System.setProperty(CloudModule.ConfigKeys.REGISTRY_TOKEN, "s3cret");
        try (AppRuntime app = FreewayApp.create(
                new HttpModule(), new CloudDiscoveryModule(), new RegistryServerModule()).start()) {
            HttpResponse<String> index = http(app, HttpRequest.newBuilder(
                uri(app, RegistryServerModule.INDEX)).GET());
            assertTrue(index.statusCode() == 200,
                "the self-description must be readable without the token, got " + index.statusCode());
            assertTrue(index.body().contains("\"proto\":" + RegistryServerModule.PROTO),
                "the index must announce the protocol: " + index.body());
            assertTrue(index.body().contains("discover"),
                "and the routes it serves: " + index.body());
            assertTrue(index.body().contains("ready"),
                "the index derives from the served table, so a route added there "
                    + "cannot be missing here: " + index.body());
            assertTrue(index.body().contains("\"index\""),
                "including the index itself: " + index.body());
            assertTrue(String.valueOf(RegistryServerModule.PROTO).equals(
                    index.headers().firstValue(RegistryServerModule.PROTO_HEADER).orElse("")),
                "responses echo the protocol, got header "
                    + index.headers().map());
        } finally {
            System.clearProperty(CloudModule.ConfigKeys.REGISTRY_TOKEN);
        }
    }

    @Test
    void an_older_protocol_is_accepted() throws Exception {
        // The documented promise: a server keeps accepting older clients.
        // Equality made a proto-0 client a 426 — a version answer for a client
        // the node could have served.
        try (AppRuntime app = FreewayApp.create(
                new HttpModule(), new CloudDiscoveryModule(), new RegistryServerModule()).start()) {
            HttpResponse<String> older = http(app, HttpRequest.newBuilder(
                    uri(app, "/registry/services/svc/instances"))
                .header(RegistryServerModule.PROTO_HEADER, "0")
                .POST(HttpRequest.BodyPublishers.ofString(INSTANCE)));
            assertTrue(older.statusCode() != 426,
                "an older protocol must be served, got " + older.statusCode()
                    + ": " + older.body());
        }
    }

    @Test
    void an_unsupported_protocol_is_refused_with_426_not_404() throws Exception {
        try (AppRuntime app = FreewayApp.create(
                new HttpModule(), new CloudDiscoveryModule(), new RegistryServerModule()).start()) {
            HttpResponse<String> newer = http(app, HttpRequest.newBuilder(
                    uri(app, "/registry/services/svc/instances"))
                .header(RegistryServerModule.PROTO_HEADER, "99")
                .POST(HttpRequest.BodyPublishers.ofString(INSTANCE)));
            assertTrue(newer.statusCode() == 426,
                "a newer protocol must be a version answer, not a routing answer, got "
                    + newer.statusCode());
            assertTrue(newer.body().contains("99"), "and it must say what was refused: " + newer.body());
        }
    }

    @Test
    void the_previous_token_is_accepted_during_rotation() throws Exception {
        System.setProperty(CloudModule.ConfigKeys.REGISTRY_TOKEN, "new-secret");
        System.setProperty(CloudModule.ConfigKeys.REGISTRY_TOKEN_PREVIOUS, "old-secret");
        try (AppRuntime app = FreewayApp.create(
                new HttpModule(), new CloudDiscoveryModule(), new RegistryServerModule()).start()) {
            HttpResponse<String> old = http(app, HttpRequest.newBuilder(
                    uri(app, "/registry/services/svc/instances"))
                .header(RegistryServerModule.TOKEN_HEADER, "old-secret")
                .POST(HttpRequest.BodyPublishers.ofString(INSTANCE)));
            assertTrue(old.statusCode() == 200,
                "the token being retired must still write during the window, got " + old.statusCode());

            HttpResponse<String> current = http(app, HttpRequest.newBuilder(
                    uri(app, "/registry/services/svc/instances"))
                .header(RegistryServerModule.TOKEN_HEADER, "new-secret")
                .POST(HttpRequest.BodyPublishers.ofString(INSTANCE)));
            assertTrue(current.statusCode() == 200, "the new token writes too");

            HttpResponse<String> stranger = http(app, HttpRequest.newBuilder(
                    uri(app, "/registry/services/svc/instances"))
                .header(RegistryServerModule.TOKEN_HEADER, "neither")
                .POST(HttpRequest.BodyPublishers.ofString(INSTANCE)));
            assertTrue(stranger.statusCode() == 401,
                "two live secrets is a window, not an open door, got " + stranger.statusCode());
        } finally {
            System.clearProperty(CloudModule.ConfigKeys.REGISTRY_TOKEN);
            System.clearProperty(CloudModule.ConfigKeys.REGISTRY_TOKEN_PREVIOUS);
        }
    }

    @Test
    void a_set_token_rejects_writers_without_the_header() throws Exception {
        System.setProperty(CloudModule.ConfigKeys.REGISTRY_TOKEN, "s3cret");
        try (AppRuntime app = FreewayApp.create(
                new HttpModule(), new CloudDiscoveryModule(), new RegistryServerModule()).start()) {
            HttpResponse<String> bare = http(app, HttpRequest.newBuilder(
                    uri(app, "/registry/services/svc/instances"))
                .POST(HttpRequest.BodyPublishers.ofString(INSTANCE)));
            assertTrue(bare.statusCode() == 401,
                "a write without the token must be refused, got " + bare.statusCode());

            HttpResponse<String> peek = http(app, HttpRequest.newBuilder(
                    uri(app, "/registry/services/svc/instances")).GET());
            assertTrue(peek.statusCode() == 401,
                "reads are writes to the attacker's map too — a set token guards them as well");
        } finally {
            System.clearProperty(CloudModule.ConfigKeys.REGISTRY_TOKEN);
        }
    }

    @Test
    void the_matching_token_passes_and_a_wrong_one_does_not() throws Exception {
        System.setProperty(CloudModule.ConfigKeys.REGISTRY_TOKEN, "s3cret");
        try (AppRuntime app = FreewayApp.create(
                new HttpModule(), new CloudDiscoveryModule(), new RegistryServerModule()).start()) {
            HttpResponse<String> wrong = http(app, HttpRequest.newBuilder(
                    uri(app, "/registry/services/svc/instances"))
                .header(RegistryServerModule.TOKEN_HEADER, "nope")
                .POST(HttpRequest.BodyPublishers.ofString(INSTANCE)));
            assertTrue(wrong.statusCode() == 401, "a wrong token must not write, got " + wrong.statusCode());

            HttpResponse<String> right = http(app, HttpRequest.newBuilder(
                    uri(app, "/registry/services/svc/instances"))
                .header(RegistryServerModule.TOKEN_HEADER, "s3cret")
                .POST(HttpRequest.BodyPublishers.ofString(INSTANCE)));
            assertTrue(right.statusCode() == 200,
                "the matching token writes, got " + right.statusCode());
        } finally {
            System.clearProperty(CloudModule.ConfigKeys.REGISTRY_TOKEN);
        }
    }
}
