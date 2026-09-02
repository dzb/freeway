package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.context.PrincipalContext;
import com.jujin.freeway.cloud.context.Propagator;
import com.jujin.freeway.commons.config.ConfigSpec;
import com.jujin.freeway.ioc.symbol.SymbolSource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Verified-identity propagation: injects the current
 * {@link PrincipalContext} as {@code x-principal} / {@code x-principal-roles}
 * headers (the verified identity, NOT raw credentials — Authorization tokens
 * are an ext concern with real token verification).
 *
 * <p><b>Trust boundary:</b> inbound extraction trusts the headers — a client
 * can send {@code x-principal: admin} and claim any identity. It is therefore
 * <b>disabled by default</b> and must be turned on explicitly
 * ({@code freeway.cloud.auth.extract.enabled=true}) and only inside a trusted
 * service mesh / behind an ext token-verifying security module. Outbound
 * injection is always on: it forwards a locally verified identity.
 */
public final class AuthPropagator implements Propagator {

    public static final String HEADER_PRINCIPAL = "x-principal";
    public static final String HEADER_ROLES = "x-principal-roles";

    /** Off by default: see the trust boundary above. */
    private static final ConfigSpec<Boolean> EXTRACT_ENABLED = ConfigSpec.of(
        CloudConfigKeys.AUTH_EXTRACT_ENABLED, Boolean.class, false, Boolean::parseBoolean);

    private final boolean extractEnabled;

    public AuthPropagator(SymbolSource symbols) {
        this.extractEnabled = EXTRACT_ENABLED.parse(symbols.resolve(EXTRACT_ENABLED.key(), null));
    }

    @Override
    public void inject(InvocationContext ctx, Map<String, String> headers) {
        PrincipalContext principal = ctx.principal();
        if (principal == null) {
            return;
        }
        headers.put(HEADER_PRINCIPAL, principal.name());
        if (!principal.roles().isEmpty()) {
            // Roles share the baggage wire codec: a role containing "," is
            // data, not a list separator, so it round-trips instead of
            // splitting into two (richer) roles on the receiving side.
            headers.put(HEADER_ROLES, principal.roles().stream()
                .map(BaggagePropagator::encode)
                .collect(java.util.stream.Collectors.joining(",")));
        }
    }

    @Override
    public InvocationContext extract(Map<String, String> headers) {
        if (!extractEnabled) {
            // Secure default: untrusted inbound identity headers are ignored.
            return InvocationContext.of(null, null, null);
        }
        String name = headers.get(HEADER_PRINCIPAL);
        if (name == null || name.isBlank()) {
            return InvocationContext.of(null, null, null);
        }
        String rolesHeader = headers.get(HEADER_ROLES);
        List<String> roles = rolesHeader == null ? List.of()
            : Arrays.stream(rolesHeader.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(BaggagePropagator::decode)
                .toList();
        return InvocationContext.of(null, PrincipalContext.of(name, roles), null);
    }
}
