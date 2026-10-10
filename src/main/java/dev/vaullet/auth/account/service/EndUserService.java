package dev.vaullet.auth.account.service;

import dev.vaullet.auth.account.dao.AccountRepository;
import dev.vaullet.auth.account.dao.AccountRepository.AccountRow;
import dev.vaullet.auth.account.dao.KeycloakUsers;
import dev.vaullet.auth.account.dao.KeycloakUsers.KeycloakUser;
import dev.vaullet.auth.account.dao.KeycloakUsers.NewKeycloakUser;
import dev.vaullet.auth.common.error.exception.AccountClosedException;
import dev.vaullet.auth.common.error.exception.EmailTakenException;
import dev.vaullet.auth.common.error.exception.ExternalRefTakenException;
import dev.vaullet.auth.common.error.exception.IdentityAlreadyLinkedException;
import dev.vaullet.auth.common.error.exception.UsernameTakenException;
import dev.vaullet.common.error.ResourceNotFoundException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * End users in local identity mode: the account and its Keycloak user, created, read, changed and
 * closed together (ADR-014).
 *
 * <p><b>Two systems, no transaction, and a create that is safe to repeat.</b> ADR-014 §6 settles
 * the partial failures by making the operation idempotent rather than transactional, and the order
 * here is what makes that true:
 *
 * <ol>
 *   <li>The account id is minted <em>first</em>, so the Keycloak user is born carrying it as its
 *       {@code account_id} attribute. The id is the anchor's primary key; nothing is written back
 *       later, so there is no third write to fail.
 *   <li>The Keycloak user is created, and a 409 is not an error yet. A user this service made for
 *       this request is recognisable — it carries an {@code account_id} and the requested email
 *       address — and is finished rather than refused. That is what heals a create that stopped
 *       between the two systems, without waiting for step 2's first-login path.
 *   <li>The anchor is inserted. This is the commit point: once the row exists, the account exists.
 *   <li>{@code END_USER} is granted, <em>after</em> the insert. A user whose anchor never got
 *       written therefore holds no role, and a grant that failed is repeated by the replay of the
 *       retry, which grants again.
 * </ol>
 *
 * <p>What this cannot heal on its own is a Keycloak user orphaned by a lost race for the same
 * {@code external_ref}: it carries an {@code account_id} no anchor has. It holds no role, so it can
 * do nothing, and ADR-014's scheduled report of realm users without an anchor is where it surfaces.
 *
 * <p>Only registered with {@code auth.provider: local}. Authorisation sits here, as it does on
 * {@link AccountService}, and for the same reason.
 */
@Service
@ConditionalOnProperty(name = "auth.provider", havingValue = "local", matchIfMissing = true)
public class EndUserService {

    /** The realm role every account created here gets, and the only one: this service makes end users. */
    static final String END_USER = "END_USER";

    private static final Logger log = LoggerFactory.getLogger(EndUserService.class);

    private final AccountRepository accounts;
    private final KeycloakUsers keycloak;

    EndUserService(AccountRepository accounts, KeycloakUsers keycloak) {
        this.accounts = accounts;
        this.keycloak = keycloak;
    }

    /**
     * Create an end user, or return the one this request already created.
     *
     * <p>Not {@code @Transactional}. Holding a database connection across calls to Keycloak would
     * tie the pool to Keycloak's latency, and the one write here is a single statement.
     */
    @PreAuthorize(AccountAccess.USER_ADMIN)
    public Account create(NewEndUser command) {
        Optional<AccountRow> byReference = accounts.findByExternalRef(command.getExternalRef());
        if (byReference.isPresent()) {
            return replayByReference(byReference.get(), command);
        }

        keycloak.requireAccountIdEditableByAdminsOnly();

        UUID accountId = UUID.randomUUID();
        Optional<UUID> created = keycloak.create(new NewKeycloakUser(
                command.getUsername(),
                command.getEmail(),
                command.getFirstName().orElse(null),
                command.getLastName().orElse(null),
                command.isEmailVerified(),
                command.getTemporaryPassword().orElse(null),
                accountId));

        UUID subject;
        if (created.isPresent()) {
            subject = created.get();
        } else {
            KeycloakUser existing = earlierAttempt(command);
            Optional<AccountRow> anchored = accounts.findByKeycloakSub(existing.id());
            if (anchored.isPresent()) {
                return replayByIdentity(anchored.get(), command);
            }
            subject = existing.id();
            accountId = existing.accountId().orElseThrow();
            log.info("Finishing an interrupted create: Keycloak user {} has no account yet", subject);
        }

        Account account = AccountService.toDomain(
                accounts.insert(accountId, subject, command.getExternalRef(), AccountStatus.ACTIVE.name()));
        keycloak.grantRealmRole(subject, END_USER);
        return account;
    }

    /** The account's identity, read from Keycloak now. */
    @PreAuthorize(AccountAccess.READ)
    public Identity findIdentity(UUID accountId) {
        UUID subject = linkedSubject(findRow(accountId));
        return keycloak.findById(subject)
                .map(EndUserService::toIdentity)
                .orElseThrow(() -> identityNotFound(accountId));
    }

    /**
     * Change the email address and names. A new email address is unverified until the user proves
     * it; an unchanged one keeps its verification.
     */
    @PreAuthorize(AccountAccess.USER_ADMIN)
    public Identity updateIdentity(UUID accountId, IdentityChanges changes) {
        AccountRow row = findRow(accountId);
        if (AccountStatus.of(row.getStatus()) == AccountStatus.CLOSED) {
            throw new AccountClosedException(accountId);
        }
        UUID subject = linkedSubject(row);
        KeycloakUser current = keycloak.findById(subject).orElseThrow(() -> identityNotFound(accountId));

        @Nullable String email = changes.getEmail()
                .filter(requested -> !requested.equalsIgnoreCase(current.email()))
                .orElse(null);
        @Nullable String firstName = changes.getFirstName().orElse(null);
        @Nullable String lastName = changes.getLastName().orElse(null);
        if (email == null && firstName == null && lastName == null) {
            return toIdentity(current);
        }

        switch (keycloak.update(subject, email, firstName, lastName)) {
            case UPDATED -> log.info("Updated the identity of account {}", accountId);
            case NOT_FOUND -> throw identityNotFound(accountId);
            case EMAIL_TAKEN -> throw new EmailTakenException();
        }
        return keycloak.findById(subject)
                .map(EndUserService::toIdentity)
                .orElseThrow(() -> identityNotFound(accountId));
    }

    /**
     * Close the account and delete its Keycloak user (ADR-014 §8). The ledger and the anchor keep
     * {@code account_id}; the personal data goes with the Keycloak user.
     *
     * <p>Close first. Closure is ours and authoritative, and it is terminal, so once it has happened
     * the account is shut whatever becomes of the second step — and a retry that finds it already
     * closed simply finishes the deletion. Deleting a user that is already gone is not an error.
     */
    @PreAuthorize(AccountAccess.USER_ADMIN)
    public void delete(UUID accountId) {
        AccountRow row = accounts.updateStatusIfOpen(accountId, AccountStatus.CLOSED.name())
                .or(() -> accounts.findById(accountId))
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        UUID subject = row.getKeycloakSub();
        if (subject != null && keycloak.delete(subject)) {
            log.info("Closed account {} and deleted its Keycloak user", accountId);
        }
    }

    // ------------------------------------------------------------------------------------------------

    /**
     * Keycloak answered 409. Decide whether the user it already has is this request's own earlier
     * attempt, which is finished, or somebody else, which is refused.
     *
     * <p>Ours means it carries an {@code account_id}, which only this service writes, <em>and</em>
     * has the email address this request asked for. The second half matters: a different person
     * choosing a username that happens to belong to an interrupted create should get 409, not that
     * account.
     */
    private KeycloakUser earlierAttempt(NewEndUser command) {
        Optional<KeycloakUser> byUsername = keycloak.findByUsername(command.getUsername());
        if (byUsername.isEmpty()) {
            // The username is free, so the 409 was about the email address.
            throw new EmailTakenException();
        }
        KeycloakUser existing = byUsername.get();
        String email = existing.email();
        boolean ours = existing.accountId().isPresent() && email != null && email.equalsIgnoreCase(command.getEmail());
        if (!ours) {
            throw new UsernameTakenException(command.getUsername());
        }
        return existing;
    }

    /** The reference already has an account: a retry if it is the same username, a collision if not. */
    private Account replayByReference(AccountRow row, NewEndUser command) {
        UUID subject = row.getKeycloakSub();
        if (subject == null) {
            // The reference belongs to an account created without an identity.
            throw new ExternalRefTakenException(command.getExternalRef());
        }
        boolean sameUser = keycloak.findById(subject)
                .map(user -> user.username().equalsIgnoreCase(command.getUsername()))
                .orElse(false);
        if (!sameUser) {
            throw new ExternalRefTakenException(command.getExternalRef());
        }
        return finishReplay(row, subject);
    }

    /** The identity already has an account: a retry if it is under the same reference, a collision if not. */
    private Account replayByIdentity(AccountRow row, NewEndUser command) {
        UUID subject = linkedSubject(row);
        if (!command.getExternalRef().equals(row.getExternalRef())) {
            throw new IdentityAlreadyLinkedException(subject);
        }
        return finishReplay(row, subject);
    }

    private Account finishReplay(AccountRow row, UUID subject) {
        Account account = AccountService.toDomain(row);
        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new AccountClosedException(account.getAccountId());
        }
        // The attempt being repeated may have stopped between the insert and the grant.
        keycloak.grantRealmRole(subject, END_USER);
        return account;
    }

    private AccountRow findRow(UUID accountId) {
        return accounts.findById(accountId).orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
    }

    /** An unlinked anchor has no identity to read or change: provisioned without one, or federated. */
    private static UUID linkedSubject(AccountRow row) {
        UUID subject = row.getKeycloakSub();
        if (subject == null) {
            throw identityNotFound(row.getAccountId());
        }
        return subject;
    }

    private static ResourceNotFoundException identityNotFound(UUID accountId) {
        return new ResourceNotFoundException("Identity of account", accountId);
    }

    private static Identity toIdentity(KeycloakUser user) {
        Long created = user.createdTimestamp();
        return new Identity(
                user.username(),
                user.email(),
                user.firstName(),
                user.lastName(),
                user.emailVerified(),
                user.enabled(),
                created == null ? null : Instant.ofEpochMilli(created));
    }
}
