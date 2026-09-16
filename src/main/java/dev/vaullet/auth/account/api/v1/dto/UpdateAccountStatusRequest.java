package dev.vaullet.auth.account.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import dev.vaullet.auth.account.service.AccountStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code PATCH /v1/accounts/{id}} — the fraud freeze, and closure.
 *
 * <p>Bound straight to {@link AccountStatus} rather than to a string. The enum is the published set
 * of values, so OpenAPI documents them without a separate list to keep in sync, and an unknown value
 * is refused at deserialisation with a 400 rather than reaching a rule that has to guess.
 */
public final class UpdateAccountStatusRequest {

    private final AccountStatus status;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public UpdateAccountStatusRequest(AccountStatus status) {
        this.status = status;
    }

    @NotNull
    @Schema(example = "SUSPENDED", description = "ACTIVE, SUSPENDED or CLOSED. CLOSED is terminal.")
    public AccountStatus getStatus() {
        return status;
    }
}
