package dev.vaullet.auth.staff.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import dev.vaullet.auth.staff.service.StaffRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Set;

/**
 * Request body for {@code PUT /v1/staff/{id}/roles}: the complete set, replacing whatever they held.
 *
 * <p>Never empty. A staff member with no role can still sign in and do nothing, which reads like a
 * fault; switching somebody off is {@code "enabled": false}, and that says what it means.
 */
public final class StaffRolesRequest {

    private final Set<StaffRole> roles;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public StaffRolesRequest(Set<StaffRole> roles) {
        this.roles = roles;
    }

    @NotEmpty
    @Schema(example = "[\"SUPPORT_AGENT\", \"FRAUD_REVIEWER\"]", description = "At least one. Not your own.")
    public Set<@NotNull StaffRole> getRoles() {
        return roles;
    }
}
