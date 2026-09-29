package dev.vaullet.auth.account.api.v1;

import dev.vaullet.auth.account.api.v1.dto.IdentityResponse;
import dev.vaullet.auth.account.api.v1.dto.UpdateIdentityRequest;
import dev.vaullet.auth.account.service.EndUserService;
import dev.vaullet.auth.account.service.IdentityChanges;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The end-user half of {@code /v1/accounts}: an account's identity, and closing an account together
 * with it (ADR-014 §3 and §8).
 *
 * <p>A controller of its own rather than more methods on {@link AccountController}, because these
 * endpoints exist only where this deployment manages users. With {@code auth.provider: federated}
 * the whole class is absent and the routes answer 404, which is ADR-012 §4's rule for a capability a
 * deployment does not have. Creating an end user stays on {@code POST /v1/accounts}; the account is
 * the resource in both modes.
 *
 * <p>Translation only, as next door: the rules and the authorisation are {@link EndUserService}'s.
 */
@RestController
@RequestMapping("/v1/accounts")
@ConditionalOnProperty(name = "auth.provider", havingValue = "local")
@Tag(name = "Accounts")
class EndUserController {

    private final EndUserService endUsers;

    EndUserController(EndUserService endUsers) {
        this.endUsers = endUsers;
    }

    @GetMapping("/{id}/identity")
    @Operation(
            summary = "Fetch an account's identity",
            description = "Read from Keycloak on every call. Vaullet stores none of it (ADR-014 §4).")
    @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND — no such account, or it has no identity")
    @ApiResponse(responseCode = "503", description = "UPSTREAM_UNAVAILABLE — Keycloak did not answer")
    IdentityResponse getIdentity(@PathVariable UUID id) {
        return IdentityResponse.from(endUsers.findIdentity(id));
    }

    @PatchMapping("/{id}/identity")
    @Operation(
            summary = "Change an account's identity",
            description = "Email address and names. Omitted fields stay as they are; a new email address is "
                    + "marked unverified.")
    @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "EMAIL_TAKEN | ACCOUNT_CLOSED")
    @ApiResponse(responseCode = "422", description = "IDENTITY_REJECTED")
    IdentityResponse updateIdentity(@PathVariable UUID id, @Valid @RequestBody UpdateIdentityRequest request) {
        return IdentityResponse.from(endUsers.updateIdentity(
                id, new IdentityChanges(request.getEmail(), request.getFirstName(), request.getLastName())));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Close an account and erase its identity",
            description = "Sets the status to CLOSED, which is terminal, and deletes the Keycloak user. The "
                    + "account_id and every financial record keyed to it stay. Repeating the call is safe.")
    @ApiResponse(responseCode = "204", description = "Closed, and the identity erased")
    @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND")
    void delete(@PathVariable UUID id) {
        endUsers.delete(id);
    }
}
