package dev.vaullet.auth.account.api.v1;

import dev.vaullet.auth.account.api.v1.dto.AccountResponse;
import dev.vaullet.auth.account.api.v1.dto.CreateAccountRequest;
import dev.vaullet.auth.account.api.v1.dto.UpdateAccountStatusRequest;
import dev.vaullet.auth.account.service.AccountService;
import dev.vaullet.auth.account.service.NewAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * HTTP entry point for the account anchor — ADR-012's {@code /v1/accounts}.
 *
 * <p>The controller's whole job is translation: bind and validate the request, call one service
 * method, shape the response. No business logic, no transaction, no try/catch — errors propagate to
 * {@code AuthApiExceptionHandler}, which is the only place that knows about status codes.
 *
 * <p><b>No {@code @PreAuthorize} here.</b> The rules sit on {@link AccountService}, a deliberate
 * departure from the ledger: step 2 drives account creation from a Kafka listener during
 * just-in-time provisioning, and a rule on a controller does not protect a listener.
 *
 * <p>Conventions on display:
 *
 * <ul>
 *   <li><b>The major version is in the path</b> — ADR-011 rejected header versioning, because a path
 *       is greppable in logs and a missing header is an implicit version nobody notices.
 *   <li><b>Plural, noun-based resource paths.</b> The verb is the HTTP method.
 *   <li><b>201 with a {@code Location} header</b> on create, including on a replay.
 *   <li><b>The freeze is {@code PATCH}, not a sub-resource verb</b>, and it is separate from the
 *       identity endpoints arriving at step 4 — ADR-006's separation of duties falls out of the
 *       resource layout rather than being enforced on top of it.
 * </ul>
 */
@RestController
@RequestMapping("/v1/accounts")
@Tag(name = "Accounts", description = "Vaullet's own record of a user: the identifier money is keyed to, and its status")
class AccountController {

    private final AccountService accounts;

    AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping
    @Operation(
            summary = "Create an account",
            description = "Repeating the request with the same external_ref returns the original account "
                    + "rather than creating a second one, so no Idempotency-Key is needed.")
    @ApiResponse(responseCode = "201", description = "Created, or the original account replayed")
    @ApiResponse(responseCode = "409", description = "EXTERNAL_REF_TAKEN | ACCOUNT_CLOSED")
    ResponseEntity<AccountResponse> create(
            @Valid @RequestBody CreateAccountRequest request, UriComponentsBuilder uriBuilder) {

        // The wire shape becomes the domain command here, and this line is the boundary that lets one
        // change without the other. keycloak_sub is null by construction: no HTTP caller can know one.
        var account = accounts.create(new NewAccount(null, request.getExternalRef()));

        URI location = uriBuilder.path("/v1/accounts/{id}").buildAndExpand(account.getAccountId()).toUri();

        // 201 on a replay too. The alternative — 200 for the second call — makes a caller's retry path
        // behave differently from its first attempt, which is the opposite of what retry-safety is for.
        return ResponseEntity.created(location).body(AccountResponse.from(account));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch an account")
    @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND")
    AccountResponse getById(@PathVariable UUID id) {
        return AccountResponse.from(accounts.find(id));
    }

    @GetMapping("/by-ref/{externalRef}")
    @Operation(
            summary = "Fetch an account by your own identifier",
            description = "So an integrator need not store a mapping from their user id to ours (ADR-012).")
    @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND")
    AccountResponse getByExternalRef(@PathVariable String externalRef) {
        return AccountResponse.from(accounts.findByExternalRef(externalRef));
    }

    @PatchMapping("/{id}")
    @Operation(
            summary = "Change the account status",
            description = "The fraud freeze, and closure. Authoritative here rather than in Keycloak: a lock "
                    + "the operator's own directory could overrule is not a lock. Closing an already-closed "
                    + "account succeeds, so a retry is safe.")
    @ApiResponse(responseCode = "409", description = "ACCOUNT_CLOSED — closure is terminal")
    @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND")
    AccountResponse updateStatus(
            @PathVariable UUID id, @Valid @RequestBody UpdateAccountStatusRequest request) {
        return AccountResponse.from(accounts.changeStatus(id, request.getStatus()));
    }
}
