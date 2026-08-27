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
}
