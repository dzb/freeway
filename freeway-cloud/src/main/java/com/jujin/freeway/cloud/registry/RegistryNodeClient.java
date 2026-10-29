package com.jujin.freeway.cloud.registry;

import com.jujin.freeway.cloud.discovery.RegistryServerModule;
import com.jujin.freeway.cloud.internal.CloudHttpClients;
import com.jujin.freeway.commons.json.JsonArray;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.cloud.discovery.Health;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.http.MediaTypes;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The wire client for a fleet of registry nodes: writes fan out to every node,
 * reads union the answers, and any one node staying alive keeps the fleet
 * working.
 *
 * <p>The wire format is the registry node's default codec, not the
 * application's: an application that customized its own {@code JsonCodec} must
 * not silently change what the nodes are asked to parse. That is why the codec
 * here is owned, not injected.</p>
 *
 * <p>Renew doubles as per-node self-healing: a node that answers 404 (it
 * restarted, its table is empty) is re-registered on the spot, so a restarted
 * node is repopulated by the very next heartbeat of every instance instead of
 * waiting for a full failure cycle.</p>
 */
final class RegistryNodeClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RegistryNodeClient.class);

    private final RegistryNodes nodes;
    private final HttpClient http;
    private final JsonCodec codec = new JsonCodecDefault();
    /** Readiness this client last published; carried on every heartbeat so a
     *  restarted node learns it without a second call. */
    private volatile boolean ready = true;

    RegistryNodeClient(RegistryNodes nodes) {
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.http = CloudHttpClients.builder(nodes.timeout(), null).build();
    }

    /** Writes the instance to every node; every node failing fails loudly. */
    public void register(ServiceInstance instance) {
        register(instance, Health.up());
    }

    /**
     * Writes the instance with the probe state it should be registered under:
     * a warming instance goes in as {@link Health#starting()}, so it is in the
     * registry but not in discovery from the first write. The readiness bit
     * also rides the heartbeat from then on.
     */
    public void register(ServiceInstance instance, Health health) {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(health, "health");
        this.ready = health.ready();
        int failures = 0;
        for (URI node : nodes.addresses()) {
            try {
                HttpResponse<String> response = exchange(node,
                    "POST", "/registry/services/" + segment(instance.serviceId()) + "/instances"
                        + "?ready=" + health.ready(),
                    codec.toJson(instance));
                if (response.statusCode() / 100 != 2) {
                    failures++;
                    LOG.warn("Registry node {} refused a registration: {} {}",
                        node, response.statusCode(), response.body());
                }
            } catch (Exception e) {
                failures++;
                LOG.warn("Registry node {} unreachable for a registration: {}", node, e.getMessage());
            }
        }
        if (failures == nodes.addresses().size()) {
            throw new IllegalStateException(
                "no registry node accepted the registration of " + instance.instanceId());
        }
    }

    /**
     * Heartbeats every node; a node that has lost the instance (it restarted) is
     * re-registered on the spot. Answers {@code true} while any node holds the
     * instance — the caller's lost-lease self-healing keys on exactly that.
     */
    /**
     * Publishes readiness. Best-effort and immediate: the explicit route makes a
     * warming instance discoverable the moment it is ready; a node that predates
     * the route answers 404 and the bit still rides the next heartbeat, so the
     * cost of an older node is a delay, not a divergence.
     */
    public void markReady(ServiceInstance instance, boolean ready) {
        this.ready = ready;
        for (URI node : nodes.addresses()) {
            try {
                exchange(node, "POST",
                    "/registry/services/" + segment(instance.serviceId())
                        + "/instances/" + segment(instance.instanceId()) + "/ready",
                    "{\"ready\":" + ready + "}");
            } catch (Exception e) {
                LOG.debug("Registry node {} unreachable for a readiness publish: {}",
                    node, e.getMessage());
            }
        }
    }

    public boolean renew(String serviceId, String instanceId) {
        boolean aliveSomewhere = false;
        boolean lostSomewhere = false;
        for (URI node : nodes.addresses()) {
            try {
                HttpResponse<String> response = exchange(node,
                    "POST",
                    "/registry/services/" + segment(serviceId)
                        + "/instances/" + segment(instanceId) + "/renew",
                    "{\"ready\":" + ready + "}");
                if (response.statusCode() == 200) {
                    aliveSomewhere = true;
                } else if (response.statusCode() == 404) {
                    // This node no longer holds the instance (it restarted, or
                    // its lease expired). Answer "lost" and let the lifecycle
                    // hook re-register — one write fans out to every node, so
                    // the lost one is repopulated by the same call that heals
                    // the fleet. Re-registering here as well would be a second
                    // mechanism for one event, invisible to the caller's
                    // failure reporting.
                    lostSomewhere = true;
                }
            } catch (Exception e) {
                LOG.warn("Registry node {} unreachable for a heartbeat: {}", node, e.getMessage());
            }
        }
        return aliveSomewhere && !lostSomewhere;
    }

    /** Removes the instance from every node; one unreachable node must not hold shutdown. */
    public void unregister(ServiceInstance instance) {
        for (URI node : nodes.addresses()) {
            try {
                exchange(node,
                    "DELETE",
                    "/registry/services/" + segment(instance.serviceId())
                        + "/instances/" + segment(instance.instanceId()),
                    "");
            } catch (Exception e) {
                LOG.warn("Registry node {} unreachable for a deregistration: {}", node, e.getMessage());
            }
        }
    }

    /**
     * Unions the live nodes' answers by instance id. One unreachable node
     * contributes nothing; every node unreachable throws — "the fleet is gone"
     * must look like a failure, not like an empty world.
     */
    public List<ServiceInstance> instances(String serviceId) {
        Map<String, ServiceInstance> union = new LinkedHashMap<>();
        int failures = 0;
        for (URI node : nodes.addresses()) {
            try {
                HttpResponse<String> response = exchange(node,
                    "GET", "/registry/services/" + segment(serviceId) + "/instances", "");
                if (response.statusCode() / 100 != 2) {
                    failures++;
                    LOG.warn("Registry node {} answered {} to a discovery", node, response.statusCode());
                    continue;
                }
                JsonArray entries = JsonUtils.parseArray(response.body());
                for (int i = 0; i < entries.size(); i++) {
                    ServiceInstance instance = codec.convert(entries.get(i), ServiceInstance.class);
                    if (instance != null) {
                        union.putIfAbsent(instance.instanceId(), instance);
                    }
                }
            } catch (Exception e) {
                failures++;
                LOG.warn("Registry node {} unreachable for a discovery: {}", node, e.getMessage());
            }
        }
        if (failures == nodes.addresses().size()) {
            throw new IllegalStateException(
                "no registry node answered a discovery for '" + serviceId + "'");
        }
        return List.copyOf(union.values());
    }

    @Override
    public void close() {
        http.close();
    }

    private HttpResponse<String> exchange(
        URI node, String method, String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(node.resolve(path))
            .timeout(nodes.timeout())
            .header("Content-Type", MediaTypes.JSON)
            .header(RegistryServerModule.PROTO_HEADER,
                String.valueOf(RegistryServerModule.PROTO));
        if (!nodes.token().isBlank()) {
            request.header(RegistryServerModule.TOKEN_HEADER, nodes.token());
        }
        request.method(method,
            body.isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = http.send(request.build(),
            HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 426) {
            // The node announced a protocol this client does not speak. Say so
            // once, with both numbers, instead of letting it read as "refused".
            throw new IllegalStateException("registry node " + node
                + " speaks protocol " + response.body() + " but this client speaks "
                + RegistryServerModule.PROTO + " — upgrade the client");
        }
        return response;
    }

    private static String segment(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }
}
