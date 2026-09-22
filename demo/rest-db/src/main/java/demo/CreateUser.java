package demo;

import com.jujin.freeway.commons.validation.NotBlank;

/**
 * POST body for {@code /api/users}; validation failures are rejected with
 * 400 in the handler before reaching {@link UserService}.
 */
public record CreateUser(
    @NotBlank String name,
    Integer age
) {}
