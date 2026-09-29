package dev.vaullet.auth.account.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * The identity half of {@code POST /v1/accounts} in local mode: the Keycloak user to create with the
 * account.
 *
 * <p>Nested rather than flat, so the request mirrors {@code GET /v1/accounts/{id}/identity} and the
 * presence of one object decides whether an end user is created. ADR-014 §3 makes identity a
 * sub-resource of the account; the body says the same thing.
 *
 * <p>The checks here are the ones worth failing before Keycloak is called. Keycloak still applies
 * the realm's own user-profile validators and password policy, and a refusal from those comes back
 * as {@code 422 IDENTITY_REJECTED} with Keycloak's reason.
 */
public final class IdentityRequest {

    private final String username;
    private final String email;
    private final @Nullable String firstName;
    private final @Nullable String lastName;
    private final boolean emailVerified;
    private final @Nullable String password;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public IdentityRequest(
            String username,
            String email,
            @Nullable String firstName,
            @Nullable String lastName,
            @Nullable Boolean emailVerified,
            @Nullable String password) {
        this.username = username;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.emailVerified = Boolean.TRUE.equals(emailVerified);
        this.password = password;
    }

    /** Keycloak stores it in lower case, and it cannot be changed afterwards through this API. */
    @NotBlank
    @Size(min = 3, max = 255)
    @Schema(example = "jane.doe", description = "Unique in the realm. Stored in lower case; not changeable later.")
    public String getUsername() {
        return username;
    }

    @NotBlank
    @Email
    @Size(max = 255)
    @Schema(example = "jane.doe@example.com", description = "Unique in the realm.")
    public String getEmail() {
        return email;
    }

    /**
     * Optional here, but the realm's default user profile requires it of the user: leave it out and
     * Keycloak asks for it at first login.
     */
    @Size(max = 255)
    @Schema(example = "Jane", description = "If omitted, Keycloak asks the user for it at first login.")
    public @Nullable String getFirstName() {
        return firstName;
    }

    @Size(max = 255)
    @Schema(example = "Doe", description = "If omitted, Keycloak asks the user for it at first login.")
    public @Nullable String getLastName() {
        return lastName;
    }

    @Schema(example = "false", defaultValue = "false",
            description = "True only if you have already verified the address yourself, for instance during KYC.")
    public boolean isEmailVerified() {
        return emailVerified;
    }

    /**
     * A first password, set as temporary: Keycloak makes the user choose their own at first login.
     * Omit it and the user needs a password reset before they can log in.
     */
    @Size(min = 8, max = 128)
    @Schema(accessMode = Schema.AccessMode.WRITE_ONLY, format = "password",
            description = "Temporary. The user must replace it at first login. Never stored or returned by Vaullet.")
    public @Nullable String getPassword() {
        return password;
    }

    /** Only the username: the rest is personal data, and the password is a credential. */
    @Override
    public String toString() {
        return "IdentityRequest[username=" + username + "]";
    }
}
