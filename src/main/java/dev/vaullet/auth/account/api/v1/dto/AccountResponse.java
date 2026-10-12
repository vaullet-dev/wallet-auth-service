package dev.vaullet.auth.account.api.v1.dto;

import dev.vaullet.auth.account.service.Account;
import dev.vaullet.auth.account.service.AccountStatus;
import dev.vaullet.auth.account.service.EndUser;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The account as an integrator sees it.
 *
 * <p><b>The Keycloak subject is deliberately not here.</b> ADR-006's central property is that
 * downstream keys on {@code account_id} and never on {@code sub} — because a subject changes on a
 * realm re-import or an identity-provider migration, and anything keyed on it breaks. Handing the
 * value to an operator invites exactly the coupling the anchor exists to prevent, and this endpoint
 * is reachable by a machine client through token exchange, not only by admin staff at a console.
 *
 * <p>What a caller actually needs is whether the account has an identity yet, so that is what
 * {@code linked} reports. It is the one fact the subject would have told them, without the
 * identifier they should not hold.
 *
 * <p>{@code identity} is there only where the server read it anyway — the search results of
 * {@code GET /v1/accounts}, which come from Keycloak — and absent everywhere else, rather than costing
 * every account read a call to Keycloak. {@code GET /v1/accounts/{id}/identity} is the way to ask.
 */
public final class AccountResponse {

    private final UUID accountId;
    private final @Nullable String externalRef;
    private final AccountStatus status;
    private final boolean linked;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final @Nullable IdentityResponse identity;

    private AccountResponse(
            UUID accountId,
            @Nullable String externalRef,
            AccountStatus status,
            boolean linked,
            Instant createdAt,
            Instant updatedAt,
            @Nullable IdentityResponse identity) {
        this.accountId = accountId;
        this.externalRef = externalRef;
        this.status = status;
        this.linked = linked;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.identity = identity;
    }

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getAccountId(),
                account.getExternalRef().orElse(null),
                account.getStatus(),
                account.getKeycloakSub().isPresent(),
                account.getCreatedAt(),
                account.getUpdatedAt(),
                null);
    }

    /** An account with its identity, as a search found it. */
    public static AccountResponse from(EndUser endUser) {
        Account account = endUser.getAccount();
        return new AccountResponse(
                account.getAccountId(),
                account.getExternalRef().orElse(null),
                account.getStatus(),
                account.getKeycloakSub().isPresent(),
                account.getCreatedAt(),
                account.getUpdatedAt(),
                IdentityResponse.from(endUser.getIdentity()));
    }

    @Schema(example = "3f1a8c7e-2b4d-4e6f-9a01-5c7d8e9f0a1b", description = "Permanent. Every other Vaullet API keys on this.")
    public UUID getAccountId() {
        return accountId;
    }

    /** Omitted from the body when absent — the platform default is {@code non_null} inclusion. */
    @Schema(example = "acme-user-8813")
    public @Nullable String getExternalRef() {
        return externalRef;
    }

    public AccountStatus getStatus() {
        return status;
    }

    /**
     * Whether an identity has been attached yet. False means an unlinked anchor: provisioned, but
     * its user has never authenticated.
     */
    @Schema(example = "false", description = "False until the user first authenticates.")
    public boolean isLinked() {
        return linked;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Present in search results only; see the class comment. */
    @Schema(description = "Present in search results, which are read from Keycloak. Absent elsewhere.")
    public @Nullable IdentityResponse getIdentity() {
        return identity;
    }
}
