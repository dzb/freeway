package com.jujin.freeway.cloud.health;

import com.jujin.freeway.cloud.internal.ReadyHandler;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;

/**
 * Cloud-native probes (K8s semantics):
 * <ul>
 *   <li>{@code /health/live} — process liveness (always ok while serving).</li>
 *   <li>{@code /health/ready} — dependency readiness, aggregating
 *       {@link CloudHealthContributor} contributions.</li>
 * </ul>
 */
@Marker(Builtin.class)
public final class CloudHealthModule implements ModuleEx {

    @Override
    public void bind(Binder b) {
        b.contribute(Route.class)
            .add("health-live", Route.get("/health/live", ctx -> ctx.send(200, "{\"status\":\"ok\"}")));
        b.contribute(Route.class)
            .add("health-ready", Route.get("/health/ready", ReadyHandler.class));
    }
}
