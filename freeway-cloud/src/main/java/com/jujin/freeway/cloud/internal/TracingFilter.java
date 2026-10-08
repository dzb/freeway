package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.health.CloudHealthModule;
import com.jujin.freeway.cloud.observe.CloudObserveModule;
import com.jujin.freeway.cloud.observe.Tracer;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.filter.HttpFilter;
import com.jujin.freeway.http.route.RouteHandler;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One server span per inbound request, started under the propagated context
 * (a root when the caller sent no {@code traceparent}). Two things follow from
 * it: the callee side of a trace is a real hop instead of a flat line, and the
 * request's log lines carry its {@code traceId} — {@link Tracer#start} mirrors
 * trace/span ids into MDC for exactly this purpose.
 *
 * <p>Contributed by {@code CloudObserveModule}, which owns the {@link Tracer};
 * a context-only install therefore gets propagation without spans, and an
 * observe-only install gets root spans without propagation. Order puts it
 * immediately inside {@link PropagationFilter} ({@code -110} vs {@code -105}):
 * the span must be a child of the context extracted from the request, and it
 * covers every filter and handler downstream of it.</p>
 *
 * <p>The span name is {@code METHOD path}. The framework does not expose the
 * matched route template, so a path carrying identifiers produces one name per
 * request — a backend that needs low cardinality (or a route-pattern name)
 * should normalize in its own {@link Tracer} implementation.</p>
 *
 * <p>The framework's own probes and scrape endpoint are skipped: they are
 * infrastructure traffic, and tracing them would drown the application's spans
 * in every backend that keeps a per-name series. The paths come from the
 * modules that serve them rather than being restated here — and the http probe
 * is the <em>configured</em> one, so a deployment that moves
 * {@code freeway.http.health.path} keeps its probe out of the spans.</p>
 */
public final class TracingFilter implements HttpFilter {

    private final Tracer tracer;
    private final Set<String> infrastructurePaths;

    public TracingFilter(Tracer tracer, String healthPath) {
        this.tracer = Objects.requireNonNull(tracer, "tracer");
        Objects.requireNonNull(healthPath, "healthPath");
        // Set.copyOf tolerates a pathological overlap (a health path set to
        // /metrics) instead of throwing at startup.
        this.infrastructurePaths = Set.copyOf(List.of(
            healthPath,
            CloudHealthModule.LIVE_PATH,
            CloudHealthModule.READY_PATH,
            CloudObserveModule.METRICS_PATH));
    }

    @Override
    public int order() {
        return -105;
    }

    @Override
    public void doFilter(HttpContext ctx, RouteHandler next) throws Exception {
        if (infrastructurePaths.contains(ctx.path())) {
            next.handle(ctx);
            return;
        }
        Tracer.Span span = tracer.start(ctx.method() + " " + ctx.path());
        try {
            InvocationContext spanContext = span.context();
            if (spanContext == null) {
                next.handle(ctx);
            } else {
                // Bind the span as the request's context, not just as ambient
                // state: inside an existing scope (the propagation filter's)
                // the scoped binding wins, so without this the handler — and
                // every outbound call it makes — would keep reporting the
                // caller's span instead of this hop's.
                InvocationContext.runWith(spanContext, () -> {
                    next.handle(ctx);
                    return null;
                });
            }
        } catch (Exception | Error e) {
            // Recorded before close(): a backend tracer attaches the error to
            // the span it is about to report.
            span.addError(e);
            throw e;
        } finally {
            span.close();
        }
    }
}
