package com.jujin.freeway.http.filter;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.route.RouteHandler;

public final class RequestTimingFilter implements HttpFilter {
    private static final Logger LOG = LoggerFactory.getLogger(RequestTimingFilter.class);

    @Override
    public void doFilter(HttpContext ctx, RouteHandler next) throws Exception {
        if (!LOG.isDebugEnabled()) {
            next.handle(ctx);
            return;
        }
        Instant startedAt = ctx.startTime();
        try {
            next.handle(ctx);
        } finally {
            long elapsedMillis = Duration.between(startedAt, Instant.now()).toMillis();
            LOG.debug("{} {} -> {} ({} ms, id={})",
                ctx.method(), ctx.path(),
                ctx.status(), elapsedMillis, ctx.correlationId());
        }
    }
}
