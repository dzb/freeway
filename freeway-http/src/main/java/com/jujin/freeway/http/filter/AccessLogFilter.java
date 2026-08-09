package com.jujin.freeway.http.filter;

import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.route.RouteHandler;

import java.io.PrintStream;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Text access log: writes one line per request with method, path, status,
 * and elapsed milliseconds. Opt-in — wire via
 * {@code WebServerBuilder.accessLog(...)} or {@code freeway.http.access-log.enabled}.
 */
public final class AccessLogFilter implements HttpFilter {

    private final PrintStream out;

    public AccessLogFilter() {
        this(System.out);
    }

    public AccessLogFilter(PrintStream out) {
        this.out = Objects.requireNonNull(out, "out");
    }

    @Override
    public void doFilter(HttpContext ctx, RouteHandler next) throws Exception {
        long start = System.nanoTime();
        try {
            next.handle(ctx);
        } finally {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            out.println(ctx.method() + " " + ctx.path() + " " + ctx.status()
                + " " + elapsedMs + "ms");
        }
    }
}
