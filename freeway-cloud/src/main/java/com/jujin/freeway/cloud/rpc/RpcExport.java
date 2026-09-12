package com.jujin.freeway.cloud.rpc;

import java.util.Objects;

/**
 * Declares one exported RPC mapping: {@code mapping + "." + method} topics
 * served by the methods of {@code type} become reachable over
 * {@code POST /rpc/{mapping}/{method}}.
 *
 * <p><b>Export is explicit by construction.</b> Only the mappings an
 * application declares here are reachable — nothing is discovered from the
 * container or the classpath, and a mapping that was not declared answers 404.
 * That is the design's security posture (see
 * {@code docs/freeway-cloud-design.md} §5.2), and the declaration is
 * the whole configuration: no route, no codec, no handler instance is named here.
 *
 * <pre>{@code
 * binder.bind(UserHandlers.class);                              // container-managed, injected
 * binder.contribute(RpcExport.class)
 *       .add(RpcExport.of("user", UserHandlers.class));         // expose user.*
 * }</pre>
 *
 * <p><b>By type, not by instance.</b> The handler is resolved from the
 * container when the application starts, so its dependencies are injected and
 * its lifecycle is the container's. A type that is not bound fails startup with
 * an actionable message rather than answering 404 later. Export a <em>facade</em>
 * class when the service's public surface is wider than what should be exposed:
 * the exported surface is exactly the resolved instance's public methods.
 *
 * @param mapping           call-topic prefix, e.g. {@code "user"} — one path
 *                          segment, {@code [A-Za-z0-9_.]} only
 * @param type              the handler type to resolve from the container
 * @param propagateMessage  send the handler's exception message to the caller
 *                          (the exception <em>class</em> always crosses; the
 *                          message is free text and routinely carries SQL, host
 *                          names and identifiers, so it stays local by default)
 */
public record RpcExport(String mapping, Class<?> type, boolean propagateMessage) {

    public RpcExport {
        // Fail at the declaration site: a mapping names a path segment, so a bad
        // value is a wiring mistake, not a request-time condition.
        RpcPaths.validateSegment(mapping, "mapping");
        Objects.requireNonNull(type, "type");
    }

    /** A mapping whose handler exception messages stay on this side. */
    public static RpcExport of(String mapping, Class<?> type) {
        return new RpcExport(mapping, type, false);
    }

    /**
     * The same export, additionally forwarding the handler's exception message
     * to the caller. Opt in only on a mesh you control end to end.
     */
    public RpcExport propagateMessages() {
        return new RpcExport(mapping, type, true);
    }
}
