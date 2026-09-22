package demo;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.db.DbModule;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.http.HttpServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The demo's own acceptance test: one app start, the full request path
 * (route match → handler → validation → transaction → Orm → JSON response).
 * If this passes, the README's curl walkthrough works.
 */
class RestDbDemoTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    @BeforeEach
    void randomPort() {
        System.setProperty(HttpConfigKeys.SERVER_PORT, "0");
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty(HttpConfigKeys.SERVER_PORT);
    }

    @Test
    void createListAndGetGoThroughRoutesValidationAndOrm() throws Exception {
        try (AppRuntime app = FreewayApp.create(
                new UserModule(), new HttpModule(), new DbModule()).start()) {
            int port = app.get(HttpServer.class).port();

            // Bean validation: a blank name never reaches the database.
            HttpResponse<String> invalid = post(port, "/api/users", "{\"name\":\"\"}");
            assertEquals(400, invalid.statusCode(), invalid.body());

            // Create → 201 with the stored row, generated id included.
            HttpResponse<String> created =
                post(port, "/api/users", "{\"name\":\"ada\",\"age\":36}");
            assertEquals(201, created.statusCode(), created.body());
            assertTrue(created.body().contains("\"ada\""), created.body());
            var idMatcher = ID.matcher(created.body());
            assertTrue(idMatcher.find(), "response must carry the generated id: "
                + created.body());
            String id = idMatcher.group(1);

            // List → the created row is there.
            HttpResponse<String> list = get(port, "/api/users");
            assertEquals(200, list.statusCode());
            assertTrue(list.body().contains("\"ada\""), list.body());

            // Get by id → the same row.
            HttpResponse<String> one = get(port, "/api/users/" + id);
            assertEquals(200, one.statusCode(), one.body());
            assertTrue(one.body().contains("\"ada\""), one.body());

            // Unknown id → 404, not an empty 200.
            assertEquals(404, get(port, "/api/users/999999").statusCode());

            // Known path, wrong method → 405 carrying Allow (RFC 9110).
            HttpResponse<String> wrongMethod =
                send(port, HttpRequest.newBuilder(uri(port, "/api/users"))
                    .DELETE().build());
            assertEquals(405, wrongMethod.statusCode());
            String allow = wrongMethod.headers().firstValue("Allow").orElse(null);
            assertNotNull(allow, "405 must carry Allow");
            assertTrue(allow.contains("GET") && allow.contains("POST"), allow);
        }
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return send(port, HttpRequest.newBuilder(uri(port, path)).GET().build());
    }

    private static HttpResponse<String> post(int port, String path, String body)
            throws Exception {
        return send(port, HttpRequest.newBuilder(uri(port, path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private static HttpResponse<String> send(int port, HttpRequest request)
            throws Exception {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(int port, String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
