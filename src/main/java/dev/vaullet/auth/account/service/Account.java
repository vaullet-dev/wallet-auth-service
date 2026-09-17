package dev.vaullet.auth.account.service;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An account, as the rest of the application sees it.
 *
 * <p>Distinct from {@code AccountRepository.AccountRow} on purpose, even though the fields line up
 * today. The row is the shape of a table and changes when the table does; this is the shape of the
 * domain and changes when the domain does. Keeping them separate means a column rename stops at the
 * dao boundary instead of reaching the controller.
 *
 * <p>The two optional fields are exposed as {@link Optional} rather than as nullable getters. That
 * is a deliberate difference from the row type: down there the null <em>is</em> the SQL NULL and
 * hiding it would be dishonest, whereas up here a caller has to make a decision about the absence,
 * and {@code Optional} is what makes the compiler insist.
 */
public final class Account {

    private final UUID accountId;
    private final @Nullable UUID keycloakSub;
    private final @Nullable String externalRef;
    private final AccountStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;

    Account(
            UUID accountId,
            @Nullable UUID keycloakSub,
            @Nullable String externalRef,
            AccountStatus status,
            Instant createdAt,
            Instant updatedAt) {
        this.accountId = accountId;
        this.keycloakSub = keycloakSub;
        this.externalRef = externalRef;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Permanent, and what the ledger keys on. Never reissued, never reused. */
    public UUID getAccountId() {
        return accountId;
    }

    /** Empty for an unlinked anchor: provisioned before its user ever authenticated. */
    public Optional<UUID> getKeycloakSub() {
        return Optional.ofNullable(keycloakSub);
    }

    /** Empty when the operator supplied no identifier of their own. */
    public Optional<String> getExternalRef() {
        return Optional.ofNullable(externalRef);
    }

    public AccountStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Identity is the account id alone — the rest is state that changes over its lifetime. */
    @Override
    public boolean equals(Object o) {
        return o instanceof Account other && accountId.equals(other.accountId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accountId);
    }

    /** Safe to log in full: the anchor holds no personal data, in either identity mode. */
    @Override
    public String toString() {
        return "Account[accountId=" + accountId
                + ", keycloakSub=" + keycloakSub
                + ", externalRef=" + externalRef
                + ", status=" + status + "]";
    }
}
