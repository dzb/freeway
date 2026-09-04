package com.jujin.freeway.cloud.context;

import java.util.List;
import com.jujin.freeway.cloud.internal.AuthPropagator;

/**
 * Verified caller identity carried across service boundaries. Holds the
 * authenticated principal, never raw credentials — propagation rules follow
 * the security subsystem (see AuthPropagator).
 *
 * @param name  verified principal name
 * @param roles roles granted to the principal
 */
public record PrincipalContext(String name, List<String> roles) {

    public PrincipalContext {
        if (name == null) {
            throw new NullPointerException("name");
        }
        roles = List.copyOf(roles);
    }

    public static PrincipalContext of(String name) {
        return new PrincipalContext(name, List.of());
    }

    public static PrincipalContext of(String name, List<String> roles) {
        return new PrincipalContext(name, roles);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
