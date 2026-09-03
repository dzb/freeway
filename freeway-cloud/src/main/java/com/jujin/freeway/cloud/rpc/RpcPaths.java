package com.jujin.freeway.cloud.rpc;

/**
 * Wire-path arithmetic shared by the remote-CallBus consumer
 * ({@link RemoteCaller}) and server endpoint ({@code RpcEndpoint}).
 * Kept in one place so the two sides cannot drift apart.
 */
final class RpcPaths {

    private RpcPaths() {}

    /** The HTTP path for a call topic: {@code /rpc/{mapping}/{method}}. */
    static String endpoint(String mapping, String method) {
        return "/rpc/" + mapping + "/" + method;
    }

    /**
     * The route pattern one exported mapping serves. The mapping is a path
     * <i>literal</i>, not a pattern variable: a single {@code {mapping}} node
     * would make every export fight for the same route, so a second mapping
     * could never be installed.
     */
    static String routePattern(String mapping) {
        validateSegment(mapping, "mapping");
        return "/rpc/" + mapping + "/{method}";
    }

    /**
     * Rejects anything that could change the shape of the wire path (slash,
     * CR/LF, spaces, non-ASCII). Both sides call this: the consumer before
     * building a request, the exporter before contributing its route.
     */
    static void validateSegment(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                || c >= '0' && c <= '9' || c == '_' || c == '.';
            if (!ok) {
                throw new IllegalArgumentException(
                    what + " contains invalid character '" + c
                        + "' — allowed: [A-Za-z0-9_.]");
            }
        }
    }
}
