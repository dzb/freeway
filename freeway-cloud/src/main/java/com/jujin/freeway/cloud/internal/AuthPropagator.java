package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.context.Baggage;
import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.context.PrincipalContext;
import com.jujin.freeway.cloud.context.Propagator;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Verified-identity propagation: injects the current
 * {@link PrincipalContext} as {@code x-principal} / {@code x-principal-roles}
 * headers (the verified identity, NOT raw credentials — Authorization tokens
 * are an ext concern with real token verification).
 *
 * <p>Trust boundary: inbound extraction trusts the headers because the
 * service mesh / deployment topology is trusted (in-cluster network). Core
 * default is a development-grade propagation; production verification
 * (JWT/Opaque token checks) is an ext security module.
 */
public final class AuthPropagator implements Propagator {

    public static final String HEADER_PRINCIPAL = "x-principal";
    public static final String HEADER_ROLES = "x-principal-roles";

    @Override
    public void inject(InvocationContext ctx, Map<String, String> headers) {
        PrincipalContext principal = ctx.principal();
        if (principal == null) {
            return;
        }
        headers.put(HEADER_PRINCIPAL, principal.name());
        if (!principal.roles().isEmpty()) {
            headers.put(HEADER_ROLES, String.join(",", principal.roles()));
        }
    }

    @Override
    public InvocationContext extract(Map<String, String> headers) {
        String name = headers.get(HEADER_PRINCIPAL);
        if (name == null || name.isBlank()) {
            return InvocationContext.of(null, null, Baggage.empty());
        }
        String rolesHeader = headers.get(HEADER_ROLES);
        List<String> roles = rolesHeader == null ? List.of()
            : Arrays.stream(rolesHeader.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return InvocationContext.of(null, PrincipalContext.of(name, roles), Baggage.empty());
    }
}
