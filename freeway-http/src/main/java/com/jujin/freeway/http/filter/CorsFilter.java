package com.jujin.freeway.http.filter;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.HttpStatus;
import com.jujin.freeway.http.route.RouteHandler;
import com.jujin.freeway.ioc.symbol.SymbolSpec;

public final class CorsFilter implements HttpFilter {
    private static final Logger LOG = LoggerFactory.getLogger(CorsFilter.class);

    /** CORS enabled, allow all origins, common methods and headers. */
    public static final CorsFilter DEFAULT = new CorsFilter(
        true, List.of("*"),
        List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"),
        List.of("Content-Type", "Authorization"),
        List.of(), "3600", false);

    private final boolean enabled;
    private final boolean allowAll;
    private final String[] allowedOriginList;
    private final String allowedMethods;
    private final String allowedHeaders;
    private final String exposedHeaders;
    private final String maxAge;
    private final boolean allowCredentials;

    public static Builder builder() {
        return new Builder();
    }

    /** The one constructor — list-valued, so the module's config lists pass
     *  through unchanged and a comma spelling decodes at the boundary that has
     *  one ({@link SymbolSpec#splitList}). Header values echo as comma lists. */
    public CorsFilter(boolean enabled, List<String> allowedOrigins,
                      List<String> allowedMethods, List<String> allowedHeaders,
                      List<String> exposedHeaders, String maxAge,
                      boolean allowCredentials) {
        this.enabled = enabled;
        List<String> origins = allowedOrigins == null ? List.of() : allowedOrigins;
        boolean all = origins.size() == 1 && "*".equals(origins.get(0));
        this.allowAll = all;
        this.allowedOriginList = (all || origins.isEmpty())
            ? new String[0]
            : origins.toArray(String[]::new);
        if (all && allowCredentials) {
            throw new IllegalArgumentException(
                "allowCredentials=true is incompatible with allowed-origins=* — "
                    + "set explicit origins to use credentials");
        }
        this.allowedMethods = echoHeader(allowedMethods);
        this.allowedHeaders = echoHeader(allowedHeaders);
        this.exposedHeaders = echoHeader(exposedHeaders);
        this.maxAge = maxAge;
        this.allowCredentials = allowCredentials;
    }

    /** Header echo form: {@code null} when empty, entries joined by ", ". */
    private static String echoHeader(List<String> values) {
        return values == null || values.isEmpty() ? null : String.join(", ", values);
    }

    /** Returns false when CORS is disabled — this filter is then a no-op pass-through. */
    public boolean isActive() {
        return enabled;
    }

    @Override
    public int order() {
        return -100; // outermost built-in filter
    }

    @Override
    public void doFilter(HttpContext ctx, RouteHandler next) throws Exception {
        if (!enabled) {
            next.handle(ctx);
            return;
        }

        String requestOrigin = ctx.header("Origin").orElse(null);
        String acao = resolveAllowedOrigin(requestOrigin);
        if (acao != null) {
            ctx.setHeader("Access-Control-Allow-Origin", acao);
            if (!"*".equals(acao)) {
                ctx.addVary("Origin");
            }
            if (allowCredentials) {
                ctx.setHeader(
                    "Access-Control-Allow-Credentials", "true");
            }
            if (exposedHeaders != null) {
                ctx.setHeader(
                    "Access-Control-Expose-Headers", exposedHeaders);
            }
        }

        // Intercept only genuine CORS preflight (Origin + Access-Control-Request-Method).
        // Non-preflight OPTIONS pass through to route handlers.
        if ("OPTIONS".equalsIgnoreCase(ctx.method())
                && ctx.header("Access-Control-Request-Method")
                    .isPresent()) {
            if (acao == null) {
                LOG.debug("CORS preflight rejected: origin '{}'", requestOrigin);
                ctx.setStatus(HttpStatus.FORBIDDEN).output(new byte[0]);
                return;
            }
            if (allowedMethods != null) {
                ctx.setHeader(
                    "Access-Control-Allow-Methods", allowedMethods);
            }
            if (allowedHeaders != null) {
                ctx.setHeader(
                    "Access-Control-Allow-Headers", allowedHeaders);
            }
            if (maxAge != null) {
                ctx.setHeader("Access-Control-Max-Age", maxAge);
            }
            ctx.send(HttpStatus.NO_CONTENT, "");
            return;
        }

        next.handle(ctx);
    }

    public String resolveAllowedOrigin(String requestOrigin) {
        if (allowAll) {
            return "*";
        }
        if (requestOrigin == null) {
            return null;
        }
        // CRLF injection prevention: reject origins containing control characters
        if (containsControlChars(requestOrigin)) {
            LOG.debug("CORS rejected: origin contains control characters");
            return null;
        }
        for (String origin : allowedOriginList) {
            if (Objects.equals(origin, requestOrigin)) {
                return requestOrigin;
            }
        }
        return null;
    }

    private static boolean containsControlChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 && c != 0x09) { // allow tab, reject others < 0x20
                return true;
            }
            if (c == 0x7f) return true;
        }
        return false;
    }

    /**
     * Builds a filter that differs from {@link #DEFAULT} only in origins and
     * credential handling — the two things a deployment actually decides. The
     * remaining fields keep the {@code DEFAULT} values, so there is exactly one
     * place where a CORS default can drift.
     */
    public static final class Builder {
        private String allowedOrigins = "*";
        private final String allowedMethods = "GET, POST, PUT, DELETE, PATCH, OPTIONS";
        private final String allowedHeaders = "Content-Type, Authorization";
        private final String exposedHeaders = null;
        private final String maxAge = "3600";
        private boolean allowCredentials;

        public Builder allowAllOrigins() {
            this.allowedOrigins = "*";
            return this;
        }

        public Builder allowedOrigins(String origins) {
            this.allowedOrigins = origins;
            return this;
        }

        public Builder allowCredentials(boolean allow) {
            this.allowCredentials = allow;
            return this;
        }

        public CorsFilter build() {
            if ("*".equals(allowedOrigins) && allowCredentials) {
                throw new IllegalStateException(
                    "Access-Control-Allow-Origin '*' cannot be used with credentials"
                );
            }
            return new CorsFilter(true,
                SymbolSpec.splitList(allowedOrigins),
                SymbolSpec.splitList(allowedMethods),
                SymbolSpec.splitList(allowedHeaders),
                SymbolSpec.splitList(exposedHeaders),
                maxAge, allowCredentials);
        }
    }
}
