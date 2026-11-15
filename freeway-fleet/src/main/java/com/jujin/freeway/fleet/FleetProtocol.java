package com.jujin.freeway.fleet;

/**
 * The fleet wire protocol: routes, headers and version shared by the client
 * and the registry server. The wire stays under {@code /registry} for
 * compatibility with existing fleet clients; the Java API moved out of
 * freeway-cloud with 2.0.
 */
public final class FleetProtocol {

    /** Register a ServiceInstance. */
    public static final String REGISTER = "/registry/services/{serviceId}/instances";
    /** Heartbeat for one instance. */
    public static final String RENEW =
        "/registry/services/{serviceId}/instances/{instanceId}/renew";
    /** Deregister one instance. */
    public static final String UNREGISTER =
        "/registry/services/{serviceId}/instances/{instanceId}";
    /** List live and ready instances. */
    public static final String DISCOVER = "/registry/services/{serviceId}/instances";
    /** Publish readiness. */
    public static final String READY =
        "/registry/services/{serviceId}/instances/{instanceId}/ready";
    /** Self-description (protocol, routes, auth posture). */
    public static final String SELF_DESCRIPTION = "/registry";

    /** Header a writer presents when the server has a token. */
    public static final String TOKEN_HEADER = "X-Registry-Token";
    /** Client-declared/echoed protocol. */
    public static final String PROTOCOL_HEADER = "X-Registry-Proto";
    /** Current wire protocol. */
    public static final int PROTOCOL = 1;

    private FleetProtocol() {}
}
