package dev.vaullet.auth.account.service;

import dev.vaullet.auth.account.dao.AccountRepository;
import dev.vaullet.auth.common.error.exception.AccountClosedException;
import dev.vaullet.auth.common.error.exception.ExternalRefTakenException;
import dev.vaullet.auth.common.error.exception.IdentityAlreadyLinkedException;
import dev.vaullet.common.error.ResourceNotFoundException;
import jakarta.validation.Valid;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.UUID;
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
        Optional<Account> byRef = command.getExternalRef()
                .flatMap(accounts::findByExternalRef)
                .map(AccountService::toDomain);
        if (byRef.isPresent()) {
            return replay(byRef.get(), command,
                    () -> new ExternalRefTakenException(command.getExternalRef().orElseThrow()));
        }

        Optional<Account> bySub = command.getKeycloakSub()
                .flatMap(accounts::findByKeycloakSub)
                .map(AccountService::toDomain);

        if (bySub.isPresent()) {
            return replay(bySub.get(), command,
                    () -> new IdentityAlreadyLinkedException(command.getKeycloakSub().orElseThrow()));
        }

        return insert(command);
    }

    private Account insert(NewAccount command) {
        return toDomain(accounts.insert(
                UUID.randomUUID(),
                command.getKeycloakSub().orElse(null),
                command.getExternalRef().orElse(null),
                AccountStatus.ACTIVE.name()));
    }

    /**
     * Decide whether a matched account is this request repeating itself, or a different request
     * colliding with it.
     *
     * <p>It is a repeat unless the command <em>asserts</em> something the stored account does not
     * say. If anything does contradict, the conflict is reported against <b>the key that found the
     * account</b> — which is the one the caller is colliding on, and the only one that makes the
     * message true.
     *
     * <p>That distinction is worth the parameter, because getting it backwards produces error bodies
     * that are confidently wrong: reporting {@code IDENTITY_ALREADY_LINKED} when the matched account
     * was found by reference names a subject that may be linked to nothing at all. An earlier version
     * of this method chose the code from <em>which field differed</em> rather than which one matched,
     * and did exactly that. {@code AccountServiceTest} is what caught it.
     *
     * @param onCollision the conflict for the key that matched
     */
    private static Account replay(
            Account existing, NewAccount command, Supplier<RuntimeException> onCollision) {
        if (contradicts(command.getExternalRef(), existing.getExternalRef())
                || contradicts(command.getKeycloakSub(), existing.getKeycloakSub())) {
            throw onCollision.get();
        }
        // Handing a closed account back with a 201 would tell the caller it is ready to use. Closure
        // is terminal, so this is permanent rather than a state they can wait out.
        if (existing.getStatus() == AccountStatus.CLOSED) {
            throw new AccountClosedException(existing.getAccountId());
        }
        return existing;
    }

    /**
     * The command asserts a value the stored account does not have.
     *
     * <p><b>Absent is not a contradiction.</b> Omitting a field is not the same as changing it, so a
     * caller who sends less on a retry than they sent originally still gets their account back.
     *
     * <p>A stored {@code empty} against a requested value <em>is</em> counted as a contradiction, and
     * that is the one debatable line here. It means "attach an identity to an existing unlinked
     * anchor", which is a real operation — just not this one. It belongs to step 2's just-in-time
     * provisioning, which will link the row rather than refuse. Until then the API cannot reach the
     * case anyway: {@code CreateAccountRequest} carries no {@code keycloak_sub}, because no HTTP
     * caller is in a position to know one.
     */
    private static <T> boolean contradicts(Optional<T> requested, Optional<T> stored) {
        return requested.isPresent() && !requested.equals(stored);
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
