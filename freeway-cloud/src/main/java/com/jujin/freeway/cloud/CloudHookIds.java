package com.jujin.freeway.cloud;

import com.jujin.freeway.http.HttpModule;

/**
 * Central {@code RuntimeHook} names for {@code freeway-cloud}, following the
 * {@code HttpModule.SERVER_HOOK} convention. Hook ordering is a cross-module
 * contract: cloud hooks reference the HTTP server hook by constant, never by
 * a scattered string literal.
 */
public final class CloudHookIds {
    private CloudHookIds() {}

    /** Secret-store startup validation (backend type check). */
    public static final String SECRET = "freeway.cloud.secret";

    /** Object-storage startup validation (backend type check). */
    public static final String STORAGE = "freeway.cloud.storage";

    /** Resilience aggregate-mode startup validation
     *  ({@code freeway.cloud.rpc.resilience} must be {@code auto} or {@code off}). */
    public static final String RESILIENCE = "freeway.cloud.resilience";

    /** Backend-type guard for discovery/registration; runs before the HTTP server. */
    public static final String DISCOVERY = "freeway.cloud.discovery";

    /** Config validation for a config-driven static discovery; runs before the HTTP server. */
    public static final String STATIC_DISCOVERY = "freeway.cloud.discovery.static";

    /** Config validation + warm-up for a config-driven DNS discovery; runs before the HTTP server. */
    public static final String DNS_DISCOVERY = "freeway.cloud.discovery.dns";

    /** RPC export wiring: resolves the declared exports, registers their
     *  handlers on the container bus and arms the {@code /rpc/} route; runs
     *  before the HTTP server so a call can never arrive unwired. */
    public static final String RPC = "freeway.cloud.rpc";

    /** Service-member registration + heartbeat; runs after the HTTP server. */
    public static final String MEMBER = "freeway.cloud.member";

    /** MeshEventBus wiring; runs after the HTTP server (the mesh origin needs
     *  the port the node actually serves on). */
    public static final String EVENT = "freeway.cloud.event";

    /** The HTTP server hook from freeway-http, referenced for ordering. */
    public static final String HTTP_SERVER = HttpModule.SERVER_HOOK;
}
