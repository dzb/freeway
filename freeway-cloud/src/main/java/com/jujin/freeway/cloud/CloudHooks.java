package com.jujin.freeway.cloud;

import com.jujin.freeway.http.HttpModule;

/**
 * Central {@code RuntimeHook} names for {@code freeway-cloud}, following the
 * {@code HttpModule.SERVER_HOOK} convention. Hook ordering is a cross-module
 * contract: cloud hooks reference the HTTP server hook by constant, never by
 * a scattered string literal.
 */
public final class CloudHooks {
    private CloudHooks() {}

    /** Config watcher shutdown; runs before the HTTP server. */
    public static final String CONFIG = "freeway.cloud.config";

    /** Secret-store startup validation (backend type check). */
    public static final String SECRET = "freeway.cloud.secret";

    /** Object-storage startup validation (backend type check). */
    public static final String STORAGE = "freeway.cloud.storage";

    /** Registry-client connection; runs before the HTTP server. */
    public static final String DISCOVERY = "freeway.cloud.discovery";

    /** Registry registration + heartbeat; runs after the HTTP server. */
    public static final String REGISTRY = "freeway.cloud.registry";

    /** CloudEventBus wiring; runs before the HTTP server. */
    public static final String EVENTS = "freeway.cloud.events";

    /** The HTTP server hook from freeway-http, referenced for ordering. */
    public static final String HTTP_SERVER = HttpModule.SERVER_HOOK;
}
