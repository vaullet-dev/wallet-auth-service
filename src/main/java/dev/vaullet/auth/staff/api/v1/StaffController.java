package dev.vaullet.auth.staff.api.v1;

import dev.vaullet.auth.common.api.ListResponse;
import dev.vaullet.auth.common.api.PageParameters;
import dev.vaullet.auth.staff.api.v1.dto.CreateStaffRequest;
import dev.vaullet.auth.staff.api.v1.dto.StaffResponse;
import dev.vaullet.auth.staff.api.v1.dto.StaffRolesRequest;
import dev.vaullet.auth.staff.api.v1.dto.UpdateStaffRequest;
import dev.vaullet.auth.staff.service.NewStaffMember;
import dev.vaullet.auth.staff.service.StaffChanges;
import dev.vaullet.auth.staff.service.StaffMember;
import dev.vaullet.auth.staff.service.StaffService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * HTTP entry point for staff management, {@code /v1/staff}: the admin dashboard's staff pages.
 *
 * <p>Translation only, like the account controllers — bind, call one {@link StaffService} method,
 * shape the response. The rules and the authorisation are the service's.
 *
 * <p>A resource of its own rather than a kind of account. Staff have no wallet, so they have no
 * anchor, and ADR-014 §3's account resource would have to explain an {@code account_id} that does
 * not exist. Only where this deployment manages users ({@code auth.provider: local}); elsewhere the
 * routes answer 404, ADR-012 §4's rule for a capability a deployment does not have.
 */
@RestController
@RequestMapping("/v1/staff")
@ConditionalOnProperty(name = "auth.provider", havingValue = "local", matchIfMissing = true)
@Tag(name = "Staff", description = "The operator's own people — support, fraud, finance and administrators — in Keycloak")
class StaffController {

    private final StaffService staff;

    StaffController(StaffService staff) {
        this.staff = staff;
    }

    @PostMapping
    @Operation(
            summary = "Create a staff member",
            description = "With a temporary password: at first sign-in they choose their own and set up an "
                    + "authenticator app. Repeating the request returns the same staff member.")
    @ApiResponse(responseCode = "201", description = "Created, or the original staff member replayed")
    @ApiResponse(responseCode = "409", description = "USERNAME_TAKEN | EMAIL_TAKEN")
    @ApiResponse(responseCode = "422", description = "IDENTITY_REJECTED — for instance, the realm's password policy")
    @ApiResponse(responseCode = "503", description = "UPSTREAM_UNAVAILABLE — Keycloak did not answer")
    ResponseEntity<StaffResponse> create(
            @Valid @RequestBody CreateStaffRequest request, UriComponentsBuilder uriBuilder) {
        StaffMember member = staff.create(new NewStaffMember(
                request.getUsername(),
                request.getEmail(),
                request.getFirstName(),
                request.getLastName(),
                request.getRoles(),
                request.getTemporaryPassword()));
        URI location = uriBuilder.path("/v1/staff/{id}").buildAndExpand(member.getId()).toUri();
        return ResponseEntity.created(location).body(StaffResponse.from(member));
    }

    @GetMapping
    @Operation(summary = "List staff", description = "By username, a page at a time.")
    ListResponse<StaffResponse> list(@Valid @ParameterObject PageParameters page) {
        return ListResponse.of(staff.list(page.position(), page.size()), StaffResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a staff member")
    @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND — no such user, or not staff")
    StaffResponse get(@PathVariable UUID id) {
        return StaffResponse.from(staff.find(id));
    }

    @PatchMapping("/{id}")
    @Operation(
            summary = "Change a staff member",
            description = "Email address, names, and whether they can sign in. Omitted fields stay as they are.")
    @ApiResponse(responseCode = "403", description = "SELF_CHANGE_FORBIDDEN — disabling yourself")
    @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "EMAIL_TAKEN")
    StaffResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateStaffRequest request) {
        return StaffResponse.from(staff.update(id, new StaffChanges(
                request.getEmail(), request.getFirstName(), request.getLastName(), request.getEnabled())));
    }

    @PutMapping("/{id}/roles")
    @Operation(
            summary = "Set a staff member's roles",
            description = "The complete set, replacing what they held. Takes effect at their next token.")
    @ApiResponse(responseCode = "403", description = "SELF_CHANGE_FORBIDDEN — your own roles")
    @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND")
    StaffResponse replaceRoles(@PathVariable UUID id, @Valid @RequestBody StaffRolesRequest request) {
        return StaffResponse.from(staff.replaceRoles(id, request.getRoles()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Delete a staff member",
            description = "Final. To switch a leaver off and keep the record, PATCH enabled to false instead.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "403", description = "SELF_CHANGE_FORBIDDEN — yourself")
    @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND")
    void delete(@PathVariable UUID id) {
        staff.delete(id);
    }
}
