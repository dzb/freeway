package com.jujin.freeway.http.filter;

import java.io.PrintStream;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.route.RouteHandler;

/**
 * Text access log: writes one line per request with method, path, status,
 * elapsed milliseconds, client IP, and user agent. Opt-in — set
 * {@code freeway.http.access-log.enabled}, or contribute this filter to the
 * container when the destination is not the log.
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
            List<String> uaValues = ctx.headers("user-agent");
            String ua = uaValues.isEmpty() ? "-" : uaValues.getFirst();
            String ip = ctx.remoteAddress();
            if (ip.isEmpty()) ip = "-";
            out.println(ctx.method() + " " + ctx.path()
                + " " + ctx.status()
                + " " + elapsedMs + "ms " + ip + " " + ua);
        }
    }
}
