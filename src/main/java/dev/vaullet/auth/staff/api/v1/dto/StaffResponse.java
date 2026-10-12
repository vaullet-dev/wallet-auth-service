package dev.vaullet.auth.staff.api.v1.dto;

import dev.vaullet.auth.staff.service.StaffMember;
import dev.vaullet.auth.staff.service.StaffRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A staff member as the admin dashboard sees them, read from Keycloak at the time of the request.
 *
 * <p>The id is Keycloak's — staff have no account, so there is no {@code account_id} to give
 * instead — and it is also the {@code sub} of their tokens, which is how the dashboard can tell the
 * signed-in admin's own row from everybody else's.
 */
public final class StaffResponse {

    private final UUID staffId;
    private final String username;
    private final @Nullable String email;
    private final @Nullable String firstName;
    private final @Nullable String lastName;
    private final boolean enabled;
    private final List<StaffRole> roles;
    private final @Nullable Instant createdAt;

    private StaffResponse(
            UUID staffId,
            String username,
            @Nullable String email,
            @Nullable String firstName,
            @Nullable String lastName,
            boolean enabled,
            List<StaffRole> roles,
            @Nullable Instant createdAt) {
        this.staffId = staffId;
        this.username = username;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.enabled = enabled;
        this.roles = roles;
        this.createdAt = createdAt;
    }

    public static StaffResponse from(StaffMember member) {
        return new StaffResponse(
                member.getId(),
                member.getUsername(),
                member.getEmail().orElse(null),
                member.getFirstName().orElse(null),
                member.getLastName().orElse(null),
                member.isEnabled(),
                List.copyOf(member.getRoles()),
                member.getCreatedAt().orElse(null));
    }

    @Schema(example = "9d1c4e2a-7b3f-4c8d-a0e1-2f3b4c5d6e7f", description = "Keycloak's id, and the sub of their tokens.")
    public UUID getStaffId() {
        return staffId;
    }

    @Schema(example = "ana.support")
    public String getUsername() {
        return username;
    }

    @Schema(example = "ana@operator.example")
    public @Nullable String getEmail() {
        return email;
    }

    @Schema(example = "Ana")
    public @Nullable String getFirstName() {
        return firstName;
    }

    @Schema(example = "Kovač")
    public @Nullable String getLastName() {
        return lastName;
    }

    @Schema(description = "False for a leaver who has been switched off.")
    public boolean isEnabled() {
        return enabled;
    }

    @Schema(example = "[\"SUPPORT_AGENT\"]",
            description = "Empty only if a create stopped before granting them; repeat it, or set them.")
    public List<StaffRole> getRoles() {
        return roles;
    }

    public @Nullable Instant getCreatedAt() {
        return createdAt;
    }
}
