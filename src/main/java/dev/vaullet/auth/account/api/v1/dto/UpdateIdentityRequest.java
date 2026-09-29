package dev.vaullet.auth.account.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Request body for {@code PATCH /v1/accounts/{id}/identity}. Every field is optional, and an absent
 * one stays as it is.
 *
 * <p>No username: it is the natural key a repeated create is recognised by. No password either;
 * that is a reset, which Keycloak runs so that no password passes through this service.
 */
public final class UpdateIdentityRequest {

    private final @Nullable String email;
    private final @Nullable String firstName;
    private final @Nullable String lastName;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public UpdateIdentityRequest(@Nullable String email, @Nullable String firstName, @Nullable String lastName) {
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
    }

    @Email
    @Size(max = 255)
    @Schema(example = "jane.doe@example.org", description = "A new address is marked unverified.")
    public @Nullable String getEmail() {
        return email;
    }

    @Size(max = 255)
    @Schema(example = "Jane")
    public @Nullable String getFirstName() {
        return firstName;
    }

    @Size(max = 255)
    @Schema(example = "Doe")
    public @Nullable String getLastName() {
        return lastName;
    }
}
