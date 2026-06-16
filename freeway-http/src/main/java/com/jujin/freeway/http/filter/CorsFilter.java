package com.jujin.freeway.http.filter;

import com.jujin.freeway.ioc.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.route.RouteHandler;

public final class CorsFilter implements HttpFilter {
    private static final Logger LOG = LoggerFactory.getLogger(CorsFilter.class);
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

    public CorsFilter(
        @Value("${web.cors.enabled:true}") boolean enabled,
        @Value("${web.cors.allowed-origins:*}") String allowedOrigins,
        @Value("${web.cors.allowed-methods:GET, POST, PUT, DELETE, PATCH, OPTIONS}") String allowedMethods,
        @Value("${web.cors.allowed-headers:Content-Type, Authorization}") String allowedHeaders,
        @Value("${web.cors.exposed-headers:}") String exposedHeaders,
        @Value("${web.cors.max-age:3600}") String maxAge,
        @Value("${web.cors.allow-credentials:false}") boolean allowCredentials
    ) {
        this.enabled = enabled;
        boolean all = "*".equals(allowedOrigins);
        this.allowAll = all;
        this.allowedOriginList = all || allowedOrigins == null || allowedOrigins.isBlank()
            ? new String[0]
            : allowedOrigins.split("\\s*,\\s*");
        this.allowedMethods = allowedMethods;
        this.allowedHeaders = allowedHeaders;
        this.exposedHeaders = HttpContext.blankToNull(exposedHeaders);
        this.maxAge = maxAge;
        this.allowCredentials = allowCredentials && !all;
    }

    @Override
    public void doFilter(HttpContext ctx, RouteHandler next) throws Exception {
        if (!enabled) {
            next.handle(ctx);
            return;
        }

        String requestOrigin = ctx.header("Origin");
        String acao = resolveAllowedOrigin(requestOrigin);
        if (acao != null) {
            ctx.headerSet("Access-Control-Allow-Origin", acao);
            if (!"*".equals(acao)) {
                ctx.headerSet("Vary", "Origin");
            }
        }
        if (allowCredentials) {
            ctx.headerSet("Access-Control-Allow-Credentials", "true");
        }
        if (exposedHeaders != null) {
            ctx.headerSet("Access-Control-Expose-Headers", exposedHeaders);
        }

        if ("OPTIONS".equalsIgnoreCase(ctx.method())) {
            // preflight must still validate origin
            if (acao == null) {
                LOG.debug("CORS preflight rejected: origin '{}'", requestOrigin);
                ctx.status(403).output(new byte[0]);
                return;
            }
            if (allowedMethods != null) {
                ctx.headerSet("Access-Control-Allow-Methods", allowedMethods);
            }
            if (allowedHeaders != null) {
                ctx.headerSet("Access-Control-Allow-Headers", allowedHeaders);
            }
            if (maxAge != null) {
                ctx.headerSet("Access-Control-Max-Age", maxAge);
            }
            ctx.send(204, "");
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

    public static final class Builder {
        private String allowedOrigins = "*";
        private String allowedMethods = "GET, POST, PUT, DELETE, PATCH, OPTIONS";
        private String allowedHeaders = "Content-Type, Authorization";
        private String exposedHeaders;
        private String maxAge = "3600";
        private boolean allowCredentials;

        public Builder allowAllOrigins() {
            this.allowedOrigins = "*";
            return this;
        }

        public Builder allowedOrigins(String origins) {
            this.allowedOrigins = origins;
            return this;
        }

        public Builder allowedMethods(String methods) {
            this.allowedMethods = methods;
            return this;
        }

        public Builder allowedHeaders(String headers) {
            this.allowedHeaders = headers;
            return this;
        }

        public Builder exposedHeaders(String headers) {
            this.exposedHeaders = headers;
            return this;
        }

        public Builder maxAge(String maxAge) {
            this.maxAge = maxAge;
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
            return new CorsFilter(true, allowedOrigins, allowedMethods, allowedHeaders, exposedHeaders, maxAge, allowCredentials);
        }
    }
}
