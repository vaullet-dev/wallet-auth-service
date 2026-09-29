package dev.vaullet.auth.account.api.v1.dto;

import dev.vaullet.auth.account.service.Identity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * An account's identity, as {@code GET /v1/accounts/{id}/identity} returns it: the Keycloak user,
 * read at the time of the request and not stored by Vaullet (ADR-014 §4).
 *
 * <p>No Keycloak id, for the reason {@link AccountResponse} gives: callers key on
 * {@code account_id}, never on the subject.
 */
public final class IdentityResponse {

    private final String username;
    private final @Nullable String email;
    private final @Nullable String firstName;
    private final @Nullable String lastName;
    private final boolean emailVerified;
    private final boolean enabled;
    private final @Nullable Instant createdAt;

    private IdentityResponse(
            String username,
            @Nullable String email,
            @Nullable String firstName,
            @Nullable String lastName,
            boolean emailVerified,
            boolean enabled,
            @Nullable Instant createdAt) {
        this.username = username;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.emailVerified = emailVerified;
        this.enabled = enabled;
        this.createdAt = createdAt;
    }

    public static IdentityResponse from(Identity identity) {
        return new IdentityResponse(
                identity.getUsername(),
                identity.getEmail().orElse(null),
                identity.getFirstName().orElse(null),
                identity.getLastName().orElse(null),
                identity.isEmailVerified(),
                identity.isEnabled(),
                identity.getCreatedAt().orElse(null));
    }

    @Schema(example = "jane.doe")
    public String getUsername() {
        return username;
    }

    @Schema(example = "jane.doe@example.com")
    public @Nullable String getEmail() {
        return email;
    }

    @Schema(example = "Jane")
    public @Nullable String getFirstName() {
        return firstName;
    }

    @Schema(example = "Doe")
    public @Nullable String getLastName() {
        return lastName;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    /** Keycloak's own switch, not the fraud freeze: that is the account's {@code status}. */
    @Schema(description = "Keycloak's own switch. The fraud freeze is the account's status, not this.")
    public boolean isEnabled() {
        return enabled;
    }

    public @Nullable Instant getCreatedAt() {
        return createdAt;
    }
}
