/**
 * RPC implementation machinery — <strong>no stability promise</strong> across
 * releases.
 *
 * <p>Routing, target dispatch, resilience policy and rotating TLS material
 * live here so the public {@code cloud.rpc} package remains the application and
 * extension contract. The export wiring hook stays beside {@code RpcEndpoint},
 * whose package-private route assembly it drives.</p>
 */
package com.jujin.freeway.cloud.rpc.internal;
