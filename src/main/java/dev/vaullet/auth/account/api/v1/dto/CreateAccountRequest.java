package dev.vaullet.auth.account.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /v1/accounts}.
 *
 * <p><b>One field, and no {@code keycloak_sub}.</b> No HTTP caller is in a position to know a
 * Keycloak subject: in local mode this service mints the realm user itself (step 4), and in
 * federated mode just-in-time provisioning supplies it at first authentication (step 2). Accepting
 * one here would offer callers an operation — attaching an identity to an existing account — that
 * this endpoint does not perform.
 *
 * <p>A dedicated input type rather than the domain command is what prevents mass assignment: there
 * is simply no {@code account_id}, {@code status} or {@code keycloak_sub} field for a caller to set.
 *
 * <p>No {@code Idempotency-Key} either, uniquely on this endpoint. ADR-011 makes the header a
 * platform rule for state-changing POSTs, and this is the one resource with a natural key that
 * already carries it: a repeat with the same {@code external_ref} returns the original account. An
 * account is always created for somebody who exists in another system, so the caller holds an
 * identifier for them before they hold ours — which a reservation never does.
 */
public final class CreateAccountRequest {

    private final String externalRef;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public CreateAccountRequest(String externalRef) {
        this.externalRef = externalRef;
    }

    /**
     * The operator's own user id. Required here even though the column is nullable: an account with
     * no natural key at all would be addressable only by the id in its own response, and that one
     * call would be the single un-retryable request in the API. The nullable column exists for the
     * listener in step 2, which creates accounts keyed on a Keycloak subject instead.
     */
    @NotBlank
    @Size(max = 255)
    @Schema(example = "acme-user-8813", description = "Your own identifier for this user. Unique per deployment, and immutable once set.")
    public String getExternalRef() {
        return externalRef;
    }
}
