package com.jujin.freeway.cloud.discovery;

import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.route.RouteHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The HTTP view of the in-process registry for a standalone freeway process.
 *
 * <p>Assembly is per-JVM: the store reachable through these routes is the same
 * {@code RegistryStore} the local {@link ServiceRegistry} and
 * {@link ServiceDiscovery} defaults wrap, so a process that registers itself
 * is observed by the discovery used for its outbound dials.
 */
public final class RegistryApi implements RouteHandler {

    private final ServiceRegistry registry;
    private final ServiceDiscovery discovery;
    private final JsonCodec codec;

    public RegistryApi(ServiceRegistry registry, ServiceDiscovery discovery, JsonCodec codec) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public void handle(HttpContext ctx) throws IOException {
        String method = ctx.method();
        String serviceId = ctx.pathVar("serviceId").orElse(null);
        String instanceId = ctx.pathVar("instanceId").orElse(null);
        try {
            if ("GET".equalsIgnoreCase(method)) {
                discover(ctx, serviceId);
            } else if ("DELETE".equalsIgnoreCase(method)) {
                unregister(ctx, serviceId, instanceId);
            } else if ("POST".equalsIgnoreCase(method)) {
                if (ctx.path().endsWith("/renew")) {
                    renew(ctx, serviceId, instanceId);
                } else {
                    register(ctx);
                }
            } else {
                ctx.send(405, "");
            }
        } catch (RuntimeException e) {
            // Advertising a malformed payload must surface as a 400, without leaking
            // a stack trace that says little in a log.
            ctx.sendJson(400, Map.of("error", e.getMessage()));
        }
    }

    private void discover(HttpContext ctx, String serviceId) throws IOException {
        ctx.sendJson(200, discovery.instances(serviceId));
    }

    private void register(HttpContext ctx) throws IOException {
        String json = ctx.bodyText();
        ServiceInstance instance = codec.fromJson(json, ServiceInstance.class);
        registry.register(instance);
        ctx.sendJson(200, instance);
    }

    private void renew(HttpContext ctx, String serviceId, String instanceId) throws IOException {
        boolean alive = registry.renew(serviceId, instanceId);
        ctx.sendJson(alive ? 200 : 404, Map.of("alive", alive));
    }

    private void unregister(HttpContext ctx, String serviceId, String instanceId)
            throws IOException {
        // The store releases an instance only through the full object the
        // discovery offers, so resolve by id here rather than asking the
        // caller to resend the whole instance for a delete.
        Optional<ServiceInstance> match = discovery.instances(serviceId).stream()
            .filter(i -> i.instanceId().equals(instanceId))
            .findFirst();
        if (match.isPresent()) {
            registry.unregister(match.get());
            ctx.sendJson(200, Map.of("unregistered", true));
        } else {
            ctx.sendJson(404, Map.of("unregistered", false));
        }
    }
}
