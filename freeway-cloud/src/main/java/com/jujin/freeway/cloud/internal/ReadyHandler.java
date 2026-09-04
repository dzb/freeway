package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.health.CloudHealthContributor;
import com.jujin.freeway.cloud.health.HealthResult;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.route.RouteHandler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /health/ready} handler: aggregates every {@link CloudHealthContributor}
 * (registry connectivity, key dependencies) — 200 {@code {"status":"ok"}} when
 * all healthy, 503 otherwise.
 */
public final class ReadyHandler implements RouteHandler {


    private static final Logger LOG = LoggerFactory.getLogger(ReadyHandler.class);
    /**
     * Runs each contributor's {@code check()} off the probe thread with a timeout
     * budget. A contributor that blocks (e.g. a stalled registry lookup) must not
     * hang {@code /health/ready} — and pile up probe threads behind it — it must
     * simply mark the probe unhealthy.
     */
    private static final ExecutorService PROBE_EXECUTOR =
        Executors.newVirtualThreadPerTaskExecutor();
    private static final long READINESS_CHECK_TIMEOUT_MS = 2_000;
    private final List<CloudHealthContributor> contributors;
    private final JsonCodec jsonCodec;

    /**
     * @throws IllegalStateException on duplicate contributor names — a wiring
     *                               error, so it surfaces at construction
     *                               instead of failing every readiness probe
     */
    public ReadyHandler(List<CloudHealthContributor> contributors, JsonCodec jsonCodec) {
        Set<String> names = new HashSet<>();
        List<CloudHealthContributor> active = new ArrayList<>(contributors.size());
        for (CloudHealthContributor contributor : contributors) {
            if (!contributor.isActive()) {
                continue; // replaced by an extension binding — no stale local probe
            }
            if (!names.add(contributor.name())) {
                throw new IllegalStateException(
                    "Duplicate CloudHealthContributor name '" + contributor.name() + "'");
            }
            active.add(contributor);
        }
        this.contributors = List.copyOf(active);
        this.jsonCodec = jsonCodec;
    }

    @Override
    public void handle(HttpContext ctx) throws Exception {
        Map<String, Object> cloud = new LinkedHashMap<>();
        boolean healthy = true;
        for (CloudHealthContributor contributor : contributors) {
            HealthResult result;
            try {
                result = withTimeout(contributor);
            } catch (Exception ex) {
                // A failing (or over-budget) dependency must mark the probe
                // unhealthy, not take down the whole endpoint (or the k8s
                // readiness probe with it). The reason is logged here, not
                // returned: readiness endpoints are unauthenticated by
                // convention, and an exception message from a dependency check
                // is internal detail.
                LOG.warn("Health contributor '{}' failed or exceeded readiness budget",
                    contributor.name(), ex);
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

    private HealthResult withTimeout(CloudHealthContributor contributor) throws Exception {
        Future<HealthResult> future = PROBE_EXECUTOR.submit(contributor::check);
        try {
            return future.get(READINESS_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException(
                "health contributor '" + contributor.name()
                    + "' exceeded readiness budget (" + READINESS_CHECK_TIMEOUT_MS + "ms)");
        }
    }
}
