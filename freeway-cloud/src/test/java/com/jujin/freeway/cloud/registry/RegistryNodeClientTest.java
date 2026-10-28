package com.jujin.freeway.cloud.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.cloud.discovery.Endpoint;
import com.jujin.freeway.cloud.discovery.RegistryServerModule;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The fleet wire contract: writes reach every node, reads union them, one dead
 * node degrades the fleet instead of breaking it, and a 404 heartbeat
 * re-asserts the instance the client actually registered — never a guess.
 */
class RegistryNodeClientTest {

    private final List<HttpServer> stubs = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopStubs() {
        for (HttpServer stub : stubs) {
            stub.stop(0);
        }
        stubs.clear();
    }

    /** One stub node: records what it was asked, answers what it was told to. */
    private StubNode stub() throws IOException {
        StubNode node = new StubNode();
        stubs.add(node.server);
        return node;
    }

    private RegistryNodeClient client(StubNode... nodes) {
        List<URI> addresses = new CopyOnWriteArrayList<>();
        for (StubNode node : nodes) {
            addresses.add(URI.create("http://127.0.0.1:" + node.port));
        }
        return new RegistryNodeClient(
            new RegistryNodes(addresses, "s3cret", Duration.ofMillis(500)));
    }

    private static ServiceInstance instance(String id) {
        return ServiceInstance.of("svc", id, Endpoint.of("http", "127.0.0.1", 9000));
    }

    @Test
    void register_fans_out_to_every_node_with_the_token() throws Exception {
        StubNode first = stub();
        StubNode second = stub();
        RegistryNodeClient client = client(first, second);

        client.register(instance("i1"));

        assertEquals(1, first.registrations.size(), "node 1 got the write");
        assertEquals(1, second.registrations.size(), "node 2 got the write too — fan-out is the HA model");
        assertEquals("i1", first.registrations.get(0).instanceId());
        assertEquals("s3cret", first.lastToken, "the shared secret rides the header");
    }

    @Test
    void discover_unions_the_nodes_and_survives_one_being_down() throws Exception {
        StubNode first = stub();
        first.instancesBody =
            "[{\"serviceId\":\"svc\",\"instanceId\":\"a\","
                + "\"endpoint\":{\"scheme\":\"http\",\"host\":\"10.0.0.1\",\"port\":1,\"basePath\":\"\"},"
                + "\"metadata\":{}}]";
        StubNode second = stub();
        second.instancesBody =
            "[{\"serviceId\":\"svc\",\"instanceId\":\"a\","
                + "\"endpoint\":{\"scheme\":\"http\",\"host\":\"10.0.0.1\",\"port\":1,\"basePath\":\"\"},"
                + "\"metadata\":{}},"
                + "{\"serviceId\":\"svc\",\"instanceId\":\"b\","
                + "\"endpoint\":{\"scheme\":\"http\",\"host\":\"10.0.0.2\",\"port\":2,\"basePath\":\"\"},"
                + "\"metadata\":{}}]";
        RegistryNodeClient client = client(first, second);

        List<ServiceInstance> found = client.instances("svc");

        assertEquals(2, found.size(),
            "the same instance seen on two nodes is one instance, not two");
        assertTrue(found.stream().anyMatch(i -> i.instanceId().equals("b")),
            "an instance only one node holds is still discovered");
    }

    @Test
    void one_dead_node_degrades_discovery_instead_of_breaking_it() throws Exception {
        StubNode alive = stub();
        alive.instancesBody =
            "[{\"serviceId\":\"svc\",\"instanceId\":\"a\","
                + "\"endpoint\":{\"scheme\":\"http\",\"host\":\"10.0.0.1\",\"port\":1,\"basePath\":\"\"},"
                + "\"metadata\":{}}]";
        StubNode dead = stub();
        dead.server.stop(0); // bound, then gone: connects fail
        RegistryNodeClient client = client(alive, dead);

        List<ServiceInstance> found = client.instances("svc");

        assertEquals(1, found.size(), "the fleet answers with what the alive node holds");
    }

    @Test
    void every_node_down_is_a_failure_not_an_empty_world() throws Exception {
        StubNode dead = stub();
        dead.server.stop(0);
        RegistryNodeClient client = client(dead);

        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class, () -> client.instances("svc"),
            "'no registry reachable' must look like a failure, never like 'no instances'");
    }

    @Test
    void a_404_heartbeat_reasserts_the_registered_instance_on_that_node() throws Exception {
        StubNode restarted = stub();
        restarted.renewStatus = 404; // the node restarted: its table is empty
        RegistryNodeClient client = client(restarted);

        client.register(instance("i1"));
        client.renew("svc", "i1");

        assertEquals(2, restarted.registrations.size(),
            "the node that lost the table was re-asserted on the next heartbeat");
        assertEquals("i1", restarted.registrations.get(1).instanceId(),
            "the re-assertion is the instance the client registered, not a guess");
    }

    @Test
    void every_request_announces_the_protocol() throws Exception {
        StubNode node = stub();
        RegistryNodeClient client = client(node);

        client.register(instance("i1"));

        assertEquals(String.valueOf(RegistryServerModule.PROTO), node.lastProto,
            "the client declares its protocol so a newer node can answer 426, not 404");
    }

    @Test
    void a_node_that_answers_426_is_a_readable_failure() throws Exception {
        StubNode newer = stub();
        newer.forcedStatus = 426;
        RegistryNodeClient client = client(newer);

        IllegalStateException failure =
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> client.register(instance("i1")));
        assertTrue(failure.getMessage().contains("no registry node accepted")
                || failure.getMessage().contains("protocol"),
            "an unsupported protocol must surface, not read as 'refused': "
                + failure.getMessage());
    }

    @Test
    void a_refused_credential_fails_the_registration_loudly() throws Exception {
        StubNode guarded = stub();
        guarded.forcedStatus = 401;   // the node requires a token this client does not have
        RegistryNodeClient client = client(guarded);

        IllegalStateException failure =
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> client.register(instance("i1")));
        assertTrue(failure.getMessage().contains("no registry node accepted"),
            "a refused write must not look like a successful registration: "
                + failure.getMessage());
    }

    /** A minimal stand-in for a registry node's HTTP surface. */
    private static final class StubNode {

        private final HttpServer server;
        private final int port;
        private final List<ServiceInstance> registrations = new CopyOnWriteArrayList<>();
        private volatile String lastToken = "";
        private volatile String lastProto = "";
        private volatile String instancesBody = "[]";
        private volatile int renewStatus = 200;
        /** 0 = honour the request; otherwise answer every call with this status. */
        private volatile int forcedStatus = 0;

        private StubNode() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                "/",
                exchange -> {
                    lastToken = exchange.getRequestHeaders()
                        .getFirst(RegistryServerModule.TOKEN_HEADER);
                    lastProto = exchange.getRequestHeaders()
                        .getFirst(RegistryServerModule.PROTO_HEADER);
                    String path = exchange.getRequestURI().getPath();
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    int status = forcedStatus == 0 ? 404 : forcedStatus;
                    String response = "";
                    boolean forced = forcedStatus != 0;
                    if (!forced && "POST".equals(exchange.getRequestMethod())
                            && path.matches(".*/instances")) {
                        status = 200;
                        // The server-side parse: the body must be a real instance.
                        registrations.add(parse(body));
                        response = new String(body, StandardCharsets.UTF_8);
                    } else if (!forced && path.endsWith("/renew")) {
                        status = renewStatus;
                    } else if (!forced && "GET".equals(exchange.getRequestMethod())) {
                        status = 200;
                        response = instancesBody;
                    }
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                    if (bytes.length > 0) {
                        exchange.getResponseBody().write(bytes);
                    }
                    exchange.close();
                });
            server.start();
            port = server.getAddress().getPort();
        }

        private static ServiceInstance parse(byte[] body) {
            return new com.jujin.freeway.commons.json.JsonCodecDefault()
                .fromJson(new String(body, StandardCharsets.UTF_8), ServiceInstance.class);
        }
    }
}
