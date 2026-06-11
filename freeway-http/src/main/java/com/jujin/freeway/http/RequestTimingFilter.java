package com.jujin.freeway.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

public final class RequestTimingFilter implements HttpFilter {
    private static final Logger LOG = LoggerFactory.getLogger(RequestTimingFilter.class);

    @Override
    public void doFilter(HttpContext ctx, RouteHandler next) throws Exception {
        RequestContext rc = ctx.requestContext();
        Instant startedAt = rc.startTime();
        try {
            next.handle(ctx);
        } finally {
            long elapsedMillis = Duration.between(startedAt, Instant.now()).toMillis();
            LOG.info("{} {} -> {} ({} ms, id={})",
                ctx.method(), ctx.path(), ctx.statusCode(), elapsedMillis, rc.correlationId());
        }
    }
}
