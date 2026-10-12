package dev.vaullet.auth.staff.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Request body for {@code PATCH /v1/staff/{id}}. Every field is optional, and an absent one stays as
 * it is.
 *
 * <p>No username, which is how a repeated create recognises its member, and no password, which they
 * keep to themselves after the first sign-in. Roles have their own endpoint, so a profile edit can
 * never change what somebody is allowed to do by accident.
 */
public final class UpdateStaffRequest {

    /**
     * Present means not blank. Absent leaves a field as it is; an empty string would clear it, and
     * Bean Validation's {@code @Email} counts an empty string as valid.
     */
    private static final String NOT_BLANK = ".*\\S.*";

    private final @Nullable String email;
    private final @Nullable String firstName;
    private final @Nullable String lastName;
    private final @Nullable Boolean enabled;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public UpdateStaffRequest(
            @Nullable String email, @Nullable String firstName, @Nullable String lastName, @Nullable Boolean enabled) {
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.enabled = enabled;
    }

    @Email
    @Pattern(regexp = NOT_BLANK, message = "must not be blank")
    @Size(max = 255)
    @Schema(example = "ana.kovac@operator.example", description = "A new address is marked unverified.")
    public @Nullable String getEmail() {
        return email;
    }

    @Pattern(regexp = NOT_BLANK, message = "must not be blank")
    @Size(max = 255)
    @Schema(example = "Ana")
    public @Nullable String getFirstName() {
        return firstName;
    }

    @Pattern(regexp = NOT_BLANK, message = "must not be blank")
    @Size(max = 255)
    @Schema(example = "Kovač")
    public @Nullable String getLastName() {
        return lastName;
    }

    @Schema(example = "false", description = "False to switch a leaver off: they cannot sign in, and their sessions end. "
            + "Not on yourself.")
    public @Nullable Boolean getEnabled() {
        return enabled;
    }
}
