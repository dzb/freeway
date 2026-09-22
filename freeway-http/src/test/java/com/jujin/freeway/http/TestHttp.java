package com.jujin.freeway.http;

import javax.net.ssl.SSLContext;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.http.engine.FreewayHttpEngine;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.ioc.ModuleEx;

/**
 * The two things a test needs that {@link HttpServer#create} does not decide for it:
 * an engine to serve with, and (for the few tests that exercise {@link HttpModule}
 * itself) the module-side bindings. Everything else a test declares directly —
 * {@link HttpServerConfig} values and {@link HttpPipeline} parts — because
 * that is exactly what an application declares.
 */
public final class TestHttp {

    private static final String OVERRIDE = "test";

    /** The built-in engine, plain HTTP, container-free defaults for JSON and coercion. */
    public static HttpEngine engine() {
        return engine(null);
    }

    /** The built-in engine reporting its counters to {@code metrics} (null = nobody listening). */
    public static HttpEngine engine(Metrics metrics) {
        var wiring = FreewayHttpEngine.Wiring.defaults(
            new JsonCodecDefault(), new CoercerDefault());
        return new FreewayHttpEngine(
            metrics == null ? wiring : wiring.withMetrics(metrics));
    }

    /** The built-in engine terminating TLS with {@code sslContext}. */
    public static HttpEngine tlsEngine(SSLContext sslContext, boolean http2OverSsl) {
        return new FreewayHttpEngine(FreewayHttpEngine.Wiring.defaults(
            new JsonCodecDefault(), new CoercerDefault())
            .withSsl(sslContext, http2OverSsl));
    }

    /** Module face: the engine contract an {@link HttpModule} test wants served. */
    public static ModuleEx config(HttpServerConfig config) {
        return binder -> binder.bind(HttpServerConfig.class)
            .to(container -> config).id(OVERRIDE).primary();
    }

    /** Module face: routes contributed the way an application contributes them. */
    public static ModuleEx routes(Route... routes) {
        return binder -> {
            for (Route route : routes) {
                binder.contribute(Route.class).add(route);
            }
        };
    }

    private TestHttp() {}
}
