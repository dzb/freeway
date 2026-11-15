package com.jujin.freeway.fleet.server;

import com.jujin.freeway.cloud.discovery.ServiceHealth;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.internal.ConstantTime;

import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.http.HttpExchange;
import com.jujin.freeway.http.route.RouteHandler;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;
import com.jujin.freeway.fleet.FleetModule.ConfigKeys;
import com.jujin.freeway.fleet.FleetProtocol;
import com.jujin.freeway.http.HttpStatus;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * The HTTP surface of a Fleet Server.
 *
 * <p>It is written against the {@link LeaseStore}: the server publishes its
 * own lease store, and the store is the one subject these routes describe.
 * The discovery view is not a substitute — it returns ready instances only, so
 * a warming instance could not be deregistered through it.</p>
 */
final class FleetApi implements RouteHandler {

    /** The shared secret; the same key a client presents. */
    private static final SymbolSpec<String> TOKEN =
        SymbolSpec.of(ConfigKeys.REGISTRY_TOKEN, String.class, "");
    /** Accepted in addition during a rotation window — this side only. */
    private static final SymbolSpec<String> TOKEN_PREVIOUS =
        SymbolSpec.of(ConfigKeys.REGISTRY_TOKEN_PREVIOUS, String.class, "");

    private final LeaseStore store;
    private final JsonCodec codec;
    /** Read per request, not captured at construction: the token is the one
     *  setting an operator rotates on a live node — a rotate-by-restart rule
     *  would make every secret change an outage. */
    private final SymbolSource symbols;

    FleetApi(LeaseStore store, JsonCodec codec, SymbolSource symbols) {
        this.store = Objects.requireNonNull(store, "store");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.symbols = Objects.requireNonNull(symbols, "symbols");
    }

    /** True when {@code announced} is a protocol this node speaks: older or equal. */
    private static boolean supports(String announced) {
        try {
            return Integer.parseInt(announced.trim()) <= FleetProtocol.PROTOCOL;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    @Override
    public void handle(HttpExchange ctx) throws IOException {
        // Version first: a client that speaks a newer protocol gets a readable
        // 426 here instead of a 404 on whichever route moved. Older protocols
        // are accepted — the documented promise is "a server keeps accepting
        // older clients", and equality made a proto-0 client a 426.
        String announced = ctx.header(FleetProtocol.PROTOCOL_HEADER).orElse("");
        if (!announced.isEmpty() && !supports(announced)) {
            ctx.setHeader(FleetProtocol.PROTOCOL_HEADER,
                String.valueOf(FleetProtocol.PROTOCOL));
            ctx.sendJson(HttpStatus.UPGRADE_REQUIRED, Map.of(
                "error", "unsupported registry protocol " + announced,
                "supported", FleetProtocol.PROTOCOL));
            return;
        }
        ctx.setHeader(FleetProtocol.PROTOCOL_HEADER,
            String.valueOf(FleetProtocol.PROTOCOL));

        if (!authorized(ctx)) {
            // A shared-secret check, not an identity system: the node is
            // reachable inside a trusted network, and the token keeps an
            // accidental writer from registering into it.
            ctx.sendText(HttpStatus.UNAUTHORIZED, "");
            return;
        }
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
                } else if (ctx.path().endsWith("/ready")) {
                    ready(ctx, serviceId, instanceId);
                } else {
                    register(ctx);
                }
            } else {
                ctx.sendText(HttpStatus.METHOD_NOT_ALLOWED, "");
            }
        } catch (RuntimeException e) {
            // Advertising a malformed payload must surface as a 400, without leaking
            // a stack trace that says little in a log.
            ctx.sendJson(HttpStatus.BAD_REQUEST, Map.of("error", e.getMessage()));
        }
    }

    /**
     * Open when neither token is set; otherwise the presented header must match
     * the current token or the one being retired. Two live secrets is what makes
     * a rotation online: set the new value, move the writers, clear the old one.
     */
    private boolean authorized(HttpExchange ctx) {
        String current = symbols.resolve(TOKEN);
        String previous = symbols.resolve(TOKEN_PREVIOUS);
        if (current.isBlank() && previous.isBlank()) {
            return true;
        }
        String presented = ctx.header(FleetProtocol.TOKEN_HEADER).orElse("");
        return (!current.isBlank() && ConstantTime.isEqual(current, presented))
            || (!previous.isBlank() && ConstantTime.isEqual(previous, presented));
    }

    private void discover(HttpExchange ctx, String serviceId) throws IOException {
        ctx.sendJson(HttpStatus.OK, store.liveReadyInstances(serviceId, LeaseStore.DEFAULT_EVICTION));
    }

    private void register(HttpExchange ctx) throws IOException {
        String json = ctx.bodyText();
        ServiceInstance instance = codec.fromJson(json, ServiceInstance.class);
        // A client that already knows it is warming registers as such in one
        // write (`?ready=false`), instead of registering as ready and being
        // marked not-ready a moment later — a window in which discovery could
        // route traffic to it. Absent means ready, the pre-existing contract.
        Boolean ready = requestedReadiness(ctx);
        store.register(instance, ready != null && !ready
            ? ServiceHealth.starting()
            : ServiceHealth.up());
        ctx.sendJson(HttpStatus.OK, instance);
    }

    /**
     * Heartbeat. The optional body carries readiness ({@code {"ready":true}}),
     * so the bit rides the heartbeat an instance already sends — no extra round
     * trip. A node that ignores the body keeps the plain heartbeat.
     */
    private void renew(HttpExchange ctx, String serviceId, String instanceId) throws IOException {
        Boolean ready = requestedReadiness(ctx);
        boolean alive = ready == null
            ? store.renew(serviceId, instanceId)
            : store.renew(serviceId, instanceId, ready);
        ctx.sendJson(alive ? HttpStatus.OK : HttpStatus.NOT_FOUND, Map.of("alive", alive));
    }

    /** Explicit readiness flip, so a starting instance becomes discoverable at
     *  the moment it is ready rather than at the next heartbeat. */
    private void ready(HttpExchange ctx, String serviceId, String instanceId) throws IOException {
        Boolean ready = requestedReadiness(ctx);
        boolean applied = ready != null && store.markReady(serviceId, instanceId, ready);
        ctx.sendJson(applied ? HttpStatus.OK : HttpStatus.NOT_FOUND,
            Map.of("ready", Boolean.TRUE.equals(ready)));
    }

    private void unregister(HttpExchange ctx, String serviceId, String instanceId)
            throws IOException {
        // By id, straight at the store: an instance that is still warming must
        // be removable, and the discovery view deliberately hides those.
        boolean removed = store.unregister(serviceId, instanceId);
        ctx.sendJson(removed ? HttpStatus.OK : HttpStatus.NOT_FOUND,
            Map.of("unregistered", removed));
    }

    /** Reads {@code {"ready":…}} from the body, or {@code ?ready=} from the query. */
    private Boolean requestedReadiness(HttpExchange ctx) throws IOException {
        String body = ctx.bodyText();
        if (body != null && !body.isBlank()) {
            JsonObject node = JsonUtils.parseObject(body);
            Boolean fromBody = node == null ? null : node.getBoolean("ready");
            if (fromBody != null) {
                return fromBody;
            }
        }
        String query = ctx.queryParam("ready").orElse(null);
        return query == null ? null : Boolean.valueOf(query);
    }
}
