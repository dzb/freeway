package com.jujin.freeway.cloud.discovery;

import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.route.RouteHandler;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The registry surface describes itself: protocol version, the routes it
 * serves and whether it expects a token. {@code GET /registry}, deliberately
 * open — a client must be able to learn what it is talking to <em>before</em>
 * it has credentials, and a mismatch answered here is a readable failure
 * instead of a 404 on whichever route happened to move.
 *
 * <p>The protocol is the compatibility contract: this node accepts
 * {@link RegistryServerModule#PROTO} and every older value; a client that
 * announces a newer one is refused with {@code 426}, never guessed at. Adding a
 * route or a header is therefore a protocol bump, not a silent change.</p>
 */
final class RegistryIndexHandler implements RouteHandler {

    @Override
    public void handle(HttpContext ctx) throws IOException {
        Map<String, Object> routes = new LinkedHashMap<>();
        // Derived from the served table, so a new route cannot be missing from
        // its own self-description.
        for (RegistryServerModule.Surface surface : RegistryServerModule.SURFACE) {
            routes.put(surface.label(), surface.path());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("proto", RegistryServerModule.PROTO);
        body.put("routes", routes);
        body.put("tokenHeader", RegistryServerModule.TOKEN_HEADER);
        body.put("protoHeader", RegistryServerModule.PROTO_HEADER);
        // A rotation window is a posture a client may want to know about: an
        // operator reading this can tell whether accepting two secrets is on.
        body.put("tokenRotation", List.of("current", "previous"));
        ctx.setHeader(RegistryServerModule.PROTO_HEADER,
            String.valueOf(RegistryServerModule.PROTO));
        ctx.sendJson(200, body);
    }
}
