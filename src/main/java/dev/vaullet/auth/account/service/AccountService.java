package dev.vaullet.auth.account.service;

import dev.vaullet.auth.account.dao.AccountRepository;
import dev.vaullet.auth.common.error.exception.AccountClosedException;
import dev.vaullet.auth.common.error.exception.ExternalRefTakenException;
import dev.vaullet.auth.common.error.exception.IdentityAlreadyLinkedException;
import dev.vaullet.common.error.ResourceNotFoundException;
import jakarta.validation.Valid;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

/**
 * The account rules, and the only place a transaction begins.
 *
 * <p><b>Authorisation sits here, not on the controller.</b> The ledger puts {@code @PreAuthorize} on
 * its controllers and its own code says that is the wrong layer; this service has no such history.
 * Step 2 drives account creation from a Kafka listener during just-in-time provisioning, and a rule
 * that lives on a controller does not protect a listener. One copy, on the method it protects.
 *
 * <p>NOTE on the {@code ResourceNotFoundException} import: it comes from
 * {@code dev.vaullet.common.error}, the location in the published {@code backend-common} 0.1.1 this
 * service pins. The library's source has since moved it to {@code …error.exception}, which is a
 * breaking change awaiting a 0.2.0 release. This import changes in the same commit that bumps
 * {@code common.version}.
 *
 * <p>Roles as well as scopes, because ADR-006's separation of duties is the point: creating an
 * account is {@code SUPER_ADMIN}'s and freezing one is {@code FRAUD_REVIEWER}'s. Reads take a scope
 * alone — enumerating five roles on every getter would be a rule nobody maintains.
 */
@Validated
@Service
public class AccountService {

    private final AccountRepository accounts;

    AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    /**
     * Create an account, or return the one this request already created.
     *
     * <p>Retry-safety comes from the natural keys rather than from an {@code Idempotency-Key}, and
     * this method is where that decision is implemented. An account is always created for somebody
     * who already exists in another system — the operator's user table, or the Keycloak realm — so
     * the caller holds an identifier for them before they hold ours. A reservation has no such prior
     * identity, which is why the ledger needs a key and this does not.
     *
     * <p>Three outcomes, all decided from the request alone:
     *
     * <ul>
     *   <li>a natural key matches and nothing contradicts it — a <b>retry</b>, so the existing
     *       account comes back and no second one is minted;
     *   <li>a natural key matches and the other one disagrees — a genuine <b>conflict</b>, with a
     *       code naming which side collided;
     *   <li>neither matches — an <b>insert</b>.
     * </ul>
     *
     * <p>The pre-check cannot close the race: two concurrent creates can both reach the insert, and
     * the loser's unique violation becomes a 409 in {@code AuthApiExceptionHandler}. That is the
     * database arbitrating, which is cheaper and more correct than a lock held across a read and a
     * write.
     *
     * <p>The command carries {@link dev.vaullet.auth.account.service.validation.AtLeastOneNaturalKey},
     * so a request with neither key is refused by Bean Validation before a line of this method runs —
     * a 400 naming both fields, rather than an integrity violation from the database. That check used
     * to be four lines of {@code if} here; as a constraint on the command it is stated once and holds
     * for the step 2 listener too, which builds a {@link NewAccount} from a Keycloak event and never
     * passes through a controller.
     *
     * @param command what to create, validated on the way in
     */
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_identity:admin') and hasRole('SUPER_ADMIN')")
    public Account create(@Valid NewAccount command) {
        UUID keycloakSub = command.getKeycloakSub().orElse(null);
        String externalRef = command.getExternalRef().orElse(null);

        if (externalRef != null) {
            Optional<Account> byRef = accounts.findByExternalRef(externalRef).map(AccountService::toDomain);
            if (byRef.isPresent()) {
                Account existing = byRef.get();
                // The stored account has a different identity behind the operator's id — or none yet,
                // while this request brings one. Neither is a repeat of the same request. Linking an
                // unlinked anchor is a real operation, but it is step 2's, not a side effect of create.
                if (keycloakSub != null && !Optional.of(keycloakSub).equals(existing.getKeycloakSub())) {
                    throw new ExternalRefTakenException(externalRef);
                }
                return replayed(existing);
            }
        }
        if (keycloakSub != null) {
            Optional<Account> bySub = accounts.findByKeycloakSub(keycloakSub).map(AccountService::toDomain);
            if (bySub.isPresent()) {
                Account existing = bySub.get();
                // ADR-006's subject collision: one identity claiming two operator identifiers. Two
                // accounts for one person splits their balance, so the second claim is refused.
                if (externalRef != null && !Optional.of(externalRef).equals(existing.getExternalRef())) {
                    throw new IdentityAlreadyLinkedException(keycloakSub);
                }
                return replayed(existing);
            }
        }

        return toDomain(accounts.insert(
                UUID.randomUUID(), keycloakSub, externalRef, AccountStatus.ACTIVE.name()));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('SCOPE_identity:read')")
    public Account find(UUID accountId) {
        return accounts.findById(accountId).map(AccountService::toDomain)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('SCOPE_identity:read')")
    public Account findByExternalRef(String externalRef) {
        return accounts.findByExternalRef(externalRef).map(AccountService::toDomain)
                .orElseThrow(() -> new ResourceNotFoundException("Account", externalRef));
    }

    /**
     * Move the account to a new status — the fraud freeze, and closure.
     *
     * <p>The guard lives in the {@code UPDATE} rather than in a read before it, so a concurrent
     * change cannot slip between the decision and the write. What arrives back is therefore the row
     * as modified, or nothing.
     *
     * <p>Nothing is the interesting case, and there are exactly two reasons for it: the account does
     * not exist, or it is closed. Telling them apart with a second read is race-free <em>only</em>
     * because {@code CLOSED} is terminal — both are permanent states, so the answer cannot change
     * under the reader.
     *
     * <p>Closing an already-closed account succeeds rather than failing. A caller retrying a
     * {@code DELETE} whose response it lost should not get an error for asking twice.
     */
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_identity:admin') and hasRole('FRAUD_REVIEWER')")
    public Account changeStatus(UUID accountId, AccountStatus newStatus) {
        Optional<Account> updated =
                accounts.updateStatusIfOpen(accountId, newStatus.name()).map(AccountService::toDomain);
        if (updated.isPresent()) {
            return updated.get();
        }

        Account existing = accounts.findById(accountId).map(AccountService::toDomain)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));

        if (newStatus == AccountStatus.CLOSED) {
            return existing;
        }
        throw new AccountClosedException(accountId);
    }

    /**
     * A retry resolved to an account that has since been closed.
     *
     * <p>Handing it back with a 201 would tell the caller their account is ready to use, which it is
     * not. Closure is terminal, so this is permanent rather than a state they can wait out.
     */
    private static Account replayed(Account existing) {
        if (existing.getStatus() == AccountStatus.CLOSED) {
            throw new AccountClosedException(existing.getAccountId());
        }
        return existing;
    }

    private static Account toDomain(AccountRepository.AccountRow row) {
        return new Account(
                row.getAccountId(),
                row.getKeycloakSub(),
                row.getExternalRef(),
                AccountStatus.of(row.getStatus()),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }
}
