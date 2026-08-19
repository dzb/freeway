package com.jujin.freeway.cloud.rpc;

/**
 * Outbound remote invocation: explicit HTTP calls by logical service name —
 * no method-level RPC, no typed proxies. The callee is an ordinary Freeway
 * HTTP application (zero cloud code).
 *
 * <p>Call flow: discovery → load-balancer choose → endpoint + path →
 * context header injection → resilience orchestration → JDK
 * {@code HttpClient.send} → {@link CloudResponse}.
 */
public interface CloudHttpClient {

    /** Calls {@code serviceId}'s selected instance at {@code request.path()}. */
    CloudResponse call(String serviceId, CloudRequest request) throws CloudException;
}
