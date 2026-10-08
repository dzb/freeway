package com.jujin.freeway.cloud.health;

import com.jujin.freeway.cloud.internal.ReadyHandler;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;

import java.util.Map;

/**
 * Cloud-native probes (K8s semantics):
 * <ul>
 *   <li>{@code /health/live} — process liveness (always ok while serving).</li>
 *   <li>{@code /health/ready} — dependency readiness, aggregating
 *       {@link CloudHealthContributor} contributions. The registry
 *       contributor ships with {@code CloudDiscoveryModule} and answers
 *       {@code not registered} before registration, 503 while the drain window
 *       runs, 503 after three consecutive heartbeat failures, and otherwise the
 *       instance count; external-backend connectivity is contributed by custom
 *       registry adapters bound primary (freeway-ext ships no cloud adapters
 *       yet). Installing this module standalone yields an empty (always-ok)
 *       aggregation.</li>
 * </ul>
 */
@Marker(Builtin.class)
public final class CloudHealthModule implements ModuleEx {

    /** Liveness probe path — the one owner; {@code TracingFilter} skips it. */
    public static final String LIVE_PATH = "/health/live";
    /** Readiness probe path — the one owner; {@code TracingFilter} skips it. */
    public static final String READY_PATH = "/health/ready";

    @Override
    public void bind(Binder b) {
        // sendJson, not send with a body literal: it is what types the answer
        // application/json (send defaults to text/plain) and what routes the
        // body through the bound JsonCodec, so a substituted codec is honored
        // here as everywhere else. /health/ready and the http-side /healthz
        // already answer this way — a probe that disagreed with its neighbours
        // on content type was a downgrade, not a choice.
        b.contribute(Route.class)
            .add("freeway.cloud.health.live",
                Route.get(LIVE_PATH, ctx -> ctx.sendJson(200, Map.of("status", "ok"))));
        b.contribute(Route.class)
            .add("freeway.cloud.health.ready", Route.get(READY_PATH, ReadyHandler.class));
    }
}
