package dev.vaullet.auth.staff.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import dev.vaullet.auth.staff.service.StaffRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;

/**
 * Request body for {@code POST /v1/staff}.
 *
 * <p>Names are required, unlike an end user's: an admin knows their colleague's name, and without it
 * the realm's user profile would stop the new member at first sign-in to ask for it. The password is
 * required too — it is how a staff member gets in, since staff are not invited by email.
 */
public final class CreateStaffRequest {

    private final String username;
    private final String email;
    private final String firstName;
    private final String lastName;
    private final Set<StaffRole> roles;
    private final String temporaryPassword;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public CreateStaffRequest(
            String username,
            String email,
            String firstName,
            String lastName,
            Set<StaffRole> roles,
            String temporaryPassword) {
        this.username = username;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.roles = roles;
        this.temporaryPassword = temporaryPassword;
    }

    @NotBlank
    @Size(min = 3, max = 255)
    @Schema(example = "ana.support", description = "Unique in the realm, end users included. Stored in lower case.")
    public String getUsername() {
        return username;
    }

    @NotBlank
    @Email
    @Size(max = 255)
    @Schema(example = "ana@operator.example", description = "Unique in the realm, end users included.")
    public String getEmail() {
        return email;
    }

    @NotBlank
    @Size(max = 255)
    @Schema(example = "Ana")
    public String getFirstName() {
        return firstName;
    }

    @NotBlank
    @Size(max = 255)
    @Schema(example = "Kovač")
    public String getLastName() {
        return lastName;
    }

    @NotEmpty
    @Schema(example = "[\"SUPPORT_AGENT\"]", description = "At least one. END_USER is not a staff role.")
    public Set<@NotNull StaffRole> getRoles() {
        return roles;
    }

    @NotBlank
    @Size(min = 8, max = 128)
    @Schema(accessMode = Schema.AccessMode.WRITE_ONLY, format = "password",
            description = "Temporary: replaced at first sign-in, together with setting up an authenticator app. "
                    + "Also checked against the realm's password policy. Never stored or returned by Vaullet.")
    public String getTemporaryPassword() {
        return temporaryPassword;
    }

    /** The username only: the rest is personal data, and the password is a credential. */
    @Override
    public String toString() {
        return "CreateStaffRequest[username=" + username + "]";
    }
}
