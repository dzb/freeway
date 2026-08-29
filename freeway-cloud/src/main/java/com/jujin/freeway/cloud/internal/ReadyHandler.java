package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.health.CloudHealthContributor;
import com.jujin.freeway.cloud.health.HealthResult;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.route.RouteHandler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /health/ready} handler: aggregates every {@link CloudHealthContributor}
 * (registry connectivity, key dependencies) — 200 {@code {"status":"ok"}} when
 * all healthy, 503 otherwise.
 */
public final class ReadyHandler implements RouteHandler {


    private static final Logger LOG = LoggerFactory.getLogger(ReadyHandler.class);
    private final List<CloudHealthContributor> contributors;
    private final JsonCodec jsonCodec;

    public ReadyHandler(List<CloudHealthContributor> contributors, JsonCodec jsonCodec) {
        this.contributors = List.copyOf(contributors);
        this.jsonCodec = jsonCodec;
    }

    @Override
    public void handle(HttpContext ctx) throws Exception {
        Map<String, Object> cloud = new LinkedHashMap<>();
        boolean healthy = true;
        for (CloudHealthContributor contributor : contributors) {
            if (cloud.containsKey(contributor.name())) {
                throw new IllegalStateException(
                    "Duplicate CloudHealthContributor name '" + contributor.name() + "'");
            }
            HealthResult result;
            try {
                result = contributor.check();
            } catch (Exception ex) {
                // A failing dependency must mark the probe unhealthy, not take
                // down the whole endpoint (or the k8s readiness probe with it).
                // The reason is logged here, not returned: readiness endpoints
                // are unauthenticated by convention, and an exception message
                // from a dependency check is internal detail.
                LOG.warn("Health contributor '{}' failed", contributor.name(), ex);
                healthy = false;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("healthy", false);
                cloud.put(contributor.name(), entry);
                continue;
            }
            healthy &= result.healthy();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("healthy", result.healthy());
            if (!result.detail().isEmpty()) {
                entry.put("detail", result.detail());
            }
            cloud.put(contributor.name(), entry);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", healthy ? "ok" : "unhealthy");
        body.put("cloud", cloud);
        ctx.setHeader("Content-Type", "application/json");
        ctx.send(healthy ? 200 : 503, jsonCodec.toJson(body));
    }
}
