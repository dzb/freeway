package com.jujin.freeway.http.filter;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.HttpStatus;
import com.jujin.freeway.http.route.RouteHandler;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;

public final class CorsFilter implements HttpFilter {
    private static final Logger LOG = LoggerFactory.getLogger(CorsFilter.class);

    /** The one statement of an unset CORS policy: enabled, allow all origins,
     *  the common methods and headers, no exposure, one-hour preflight cache, no
     *  credentials. A wither starts here, so this is the only place these values
     *  are written down. */
    public static CorsFilter defaults() {
        return new CorsFilter(true, List.of("*"),
            List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"),
            List.of("Content-Type", "Authorization"),
            List.of(), "3600", false);
    }

    // The CORS keys: name and type only. `from` pins each one's default to the
    // field it overlays, so this table never restates what defaults() states.
    private static final SymbolSpec<Boolean> ENABLED =
        SymbolSpec.of(HttpConfigKeys.CORS_ENABLED, Boolean.class, null);
    private static final SymbolSpec<List<String>> ALLOWED_ORIGINS =
        SymbolSpec.list(HttpConfigKeys.CORS_ALLOWED_ORIGINS, null);
    private static final SymbolSpec<List<String>> ALLOWED_METHODS =
        SymbolSpec.list(HttpConfigKeys.CORS_ALLOWED_METHODS, null);
    private static final SymbolSpec<List<String>> ALLOWED_HEADERS =
        SymbolSpec.list(HttpConfigKeys.CORS_ALLOWED_HEADERS, null);
    private static final SymbolSpec<List<String>> EXPOSED_HEADERS =
        SymbolSpec.list(HttpConfigKeys.CORS_EXPOSED_HEADERS, null);
    private static final SymbolSpec<String> MAX_AGE =
        SymbolSpec.of(HttpConfigKeys.CORS_MAX_AGE, String.class, null);
    private static final SymbolSpec<Boolean> ALLOW_CREDENTIALS =
        SymbolSpec.of(HttpConfigKeys.CORS_ALLOW_CREDENTIALS, Boolean.class, null);

    /**
     * The policy as {@code freeway.http.cors.*} answers it: {@link #defaults()}
     * with one key overlaid per field.
     */
    public static CorsFilter from(SymbolSource symbols) {
        CorsFilter cors = defaults();
        cors = cors.withEnabled(symbols.resolve(ENABLED.orDefault(cors.enabled())));
        cors = cors.withAllowedOrigins(symbols.resolve(ALLOWED_ORIGINS.orDefault(cors.allowedOrigins())));
        cors = cors.withAllowedMethods(symbols.resolve(ALLOWED_METHODS.orDefault(cors.allowedMethods())));
        cors = cors.withAllowedHeaders(symbols.resolve(ALLOWED_HEADERS.orDefault(cors.allowedHeaders())));
        cors = cors.withExposedHeaders(symbols.resolve(EXPOSED_HEADERS.orDefault(cors.exposedHeaders())));
        cors = cors.withMaxAge(symbols.resolve(MAX_AGE.orDefault(cors.maxAge())));
        cors = cors.withAllowCredentials(
            symbols.resolve(ALLOW_CREDENTIALS.orDefault(cors.allowCredentials())));
        return cors;
    }

    private final boolean enabled;
    private final List<String> allowedOrigins;
    private final List<String> allowedMethods;
    private final List<String> allowedHeaders;
    private final List<String> exposedHeaders;
    private final String maxAge;
    private final boolean allowCredentials;

    /** Derived once at assembly, from the fields above: the origin match forms
     *  and the comma echoes a preflight response carries verbatim. */
    private final boolean allowAll;
    private final String[] originMatch;
    private final String methodsEcho;
    private final String headersEcho;
    private final String exposedEcho;

    /** The one constructor — list-valued, so the module's config lists pass
     *  through unchanged and a comma spelling decodes at the boundary that has
     *  one ({@link SymbolSpec#splitList}). Header values echo as comma lists. */
    public CorsFilter(boolean enabled, List<String> allowedOrigins,
                      List<String> allowedMethods, List<String> allowedHeaders,
                      List<String> exposedHeaders, String maxAge,
                      boolean allowCredentials) {
        this.enabled = enabled;
        this.allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        this.allowedMethods = allowedMethods == null ? List.of() : List.copyOf(allowedMethods);
        this.allowedHeaders = allowedHeaders == null ? List.of() : List.copyOf(allowedHeaders);
        this.exposedHeaders = exposedHeaders == null ? List.of() : List.copyOf(exposedHeaders);
        this.maxAge = maxAge;
        this.allowCredentials = allowCredentials;
        boolean all = this.allowedOrigins.size() == 1 && "*".equals(this.allowedOrigins.get(0));
        this.allowAll = all;
        this.originMatch = (all || this.allowedOrigins.isEmpty())
            ? new String[0]
            : this.allowedOrigins.toArray(String[]::new);
        if (all && allowCredentials) {
            throw new IllegalArgumentException(
                "allowCredentials=true is incompatible with allowed-origins=* — "
                    + "set explicit origins to use credentials");
        }
        this.methodsEcho = echoHeader(this.allowedMethods);
        this.headersEcho = echoHeader(this.allowedHeaders);
        this.exposedEcho = echoHeader(this.exposedHeaders);
    }

    /** The policy's own fields, readable so a key can default back to them. */
    public boolean enabled() {
        return enabled;
    }

    public List<String> allowedOrigins() {
        return allowedOrigins;
    }

    public List<String> allowedMethods() {
        return allowedMethods;
    }

    public List<String> allowedHeaders() {
        return allowedHeaders;
    }

    public List<String> exposedHeaders() {
        return exposedHeaders;
    }

    public String maxAge() {
        return maxAge;
    }

    public boolean allowCredentials() {
        return allowCredentials;
    }

    /** Same policy with CORS switched on or off — disabled is a pass-through. */
    public CorsFilter withEnabled(boolean enabled) {
        return new CorsFilter(enabled, allowedOrigins, allowedMethods, allowedHeaders,
            exposedHeaders, maxAge, allowCredentials);
    }

    /** Same policy with these origins allowed (comma-split upstream, at the config edge). */
    public CorsFilter withAllowedOrigins(List<String> origins) {
        return new CorsFilter(enabled, origins, allowedMethods, allowedHeaders,
            exposedHeaders, maxAge, allowCredentials);
    }

    /** Same policy answering preflight with these methods. */
    public CorsFilter withAllowedMethods(List<String> methods) {
        return new CorsFilter(enabled, allowedOrigins, methods, allowedHeaders,
            exposedHeaders, maxAge, allowCredentials);
    }

    /** Same policy answering preflight with these request headers. */
    public CorsFilter withAllowedHeaders(List<String> headers) {
        return new CorsFilter(enabled, allowedOrigins, allowedMethods, headers,
            exposedHeaders, maxAge, allowCredentials);
    }

    /** Same policy exposing these response headers to the reading script. */
    public CorsFilter withExposedHeaders(List<String> exposed) {
        return new CorsFilter(enabled, allowedOrigins, allowedMethods, allowedHeaders,
            exposed, maxAge, allowCredentials);
    }

    /** Same policy caching the preflight answer for this long (seconds). */
    public CorsFilter withMaxAge(String maxAge) {
        return new CorsFilter(enabled, allowedOrigins, allowedMethods, allowedHeaders,
            exposedHeaders, maxAge, allowCredentials);
    }

    /** Same policy allowing credentialed cross-origin requests, which needs an
     *  explicit origin set — {@code *} with credentials is rejected here too. */
    public CorsFilter withAllowCredentials(boolean allowCredentials) {
        return new CorsFilter(enabled, allowedOrigins, allowedMethods, allowedHeaders,
            exposedHeaders, maxAge, allowCredentials);
    }

    /** Header echo form: {@code null} when empty, entries joined by ", ". */
    private static String echoHeader(List<String> values) {
        return values.isEmpty() ? null : String.join(", ", values);
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
            if (exposedEcho != null) {
                ctx.setHeader(
                    "Access-Control-Expose-Headers", exposedEcho);
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
            if (methodsEcho != null) {
                ctx.setHeader(
                    "Access-Control-Allow-Methods", methodsEcho);
            }
            if (headersEcho != null) {
                ctx.setHeader(
                    "Access-Control-Allow-Headers", headersEcho);
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
        for (String origin : originMatch) {
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

}
