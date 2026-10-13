package dev.vaullet.auth.account.service;

import dev.vaullet.auth.account.dao.AccountRepository;
import dev.vaullet.auth.account.dao.AccountRepository.AccountRow;
import dev.vaullet.auth.account.dao.KeycloakUsers;
import dev.vaullet.auth.account.dao.KeycloakUsers.KeycloakUser;
import dev.vaullet.auth.account.dao.KeycloakUsers.NewKeycloakUser;
import dev.vaullet.auth.common.error.exception.AccountClosedException;
import dev.vaullet.auth.common.error.exception.EmailDeliveryUnavailableException;
import dev.vaullet.auth.common.error.exception.EmailNotVerifiedException;
import dev.vaullet.auth.common.error.exception.EmailTakenException;
import dev.vaullet.auth.common.error.exception.ExternalRefTakenException;
import dev.vaullet.auth.common.error.exception.IdentityAlreadyLinkedException;
import dev.vaullet.auth.common.error.exception.UsernameTakenException;
import dev.vaullet.auth.common.paging.Slice;
import dev.vaullet.auth.config.AuthProperties;
import dev.vaullet.common.error.exception.ResourceNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
 *   <li>The invitation goes out: Keycloak emails the user a link to set their own password, so none
 *       passes through this service. Whether email can be sent is checked before the first write,
 *       so a realm without SMTP refuses the request with nothing created. A send that fails anyway
 *       is a 503, and the retry's replay sends it again — for as long as the user has no way to sign
 *       in, which is also why a replay of a create long finished sends nothing.
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

    /** How many Keycloak users one search reads at a time. */
    private static final int SEARCH_BATCH = 100;

    /** How many batches one search page reads at most, filled or not. */
    private static final int SEARCH_BATCHES = 5;

    private final AccountRepository accounts;
    private final KeycloakUsers keycloak;
    private final Duration emailLinkLifespan;

    EndUserService(AccountRepository accounts, KeycloakUsers keycloak, AuthProperties properties) {
        this.accounts = accounts;
        this.keycloak = keycloak;
        this.emailLinkLifespan = properties.userManagement().emailLinkLifespan();
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
        requireEmailDelivery();

        UUID accountId = UUID.randomUUID();
        Optional<UUID> created = keycloak.create(NewKeycloakUser.endUser(
                command.getUsername(),
                command.getEmail(),
                command.getFirstName().orElse(null),
                command.getLastName().orElse(null),
                command.isEmailVerified(),
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
        sendSetPasswordLink(accountId, subject);
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
     * End users matching a search, a page at a time, in username order — answered from Keycloak,
     * where the identity lives (ADR-014 §4), and joined to the accounts in one query per batch.
     *
     * <p>Keycloak's list holds staff too, and users with no account; neither is an end user here, so
     * a page is filled by reading on past them. That is why a page can hold fewer than
     * {@code limit} users and still not be the last: one page reads at most {@value #SEARCH_BATCHES}
     * batches of {@value #SEARCH_BATCH}, so a long run of staff costs a short page rather than a slow
     * one. Closed accounts are not found — their identity was erased with them.
     */
    @PreAuthorize(AccountAccess.READ)
    public Slice<EndUser> search(EndUserQuery query, int position, int limit) {
        List<EndUser> found = new ArrayList<>();
        int next = position;
        for (int batch = 0; batch < SEARCH_BATCHES; batch++) {
            List<KeycloakUser> users = keycloak.search(
                    query.getText().orElse(null),
                    query.getEmail().orElse(null),
                    query.getUsername().orElse(null),
                    next,
                    SEARCH_BATCH);
            boolean lastBatch = users.size() < SEARCH_BATCH;
            Map<UUID, AccountRow> anchors = accounts.findByKeycloakSubs(users.stream().map(KeycloakUser::id).toList())
                    .stream()
                    .collect(Collectors.toMap(AccountRow::getKeycloakSub, Function.identity()));
            for (int i = 0; i < users.size(); i++) {
                KeycloakUser user = users.get(i);
                next++;
                AccountRow anchor = anchors.get(user.id());
                if (anchor == null) {
                    continue;
                }
                found.add(new EndUser(AccountService.toDomain(anchor), toIdentity(user)));
                if (found.size() == limit) {
                    boolean listEnded = lastBatch && i == users.size() - 1;
                    return new Slice<>(found, listEnded ? null : next);
                }
            }
            if (lastBatch) {
                return new Slice<>(found, null);
            }
        }
        return new Slice<>(found, next);
    }

    /**
     * Email the user a link to set a new password. For a forgotten password, and for an invitation
     * that went astray or expired: the email is the same either way, and so is what the user does
     * with it. No password passes through this service, and the user's current one keeps working
     * until they set the new one.
     *
     * <p><b>Only to a verified address, once the user can sign in.</b> The link is the account to
     * whoever reads that inbox, and {@link #updateIdentity} sets addresses nobody has proved yet —
     * so without this rule, changing the address and then asking for a reset would take over any
     * user, by anyone allowed to do both, including a {@code SUPER_ADMIN} whose role was taken away
     * after their token was issued. Refused with {@code EMAIL_NOT_VERIFIED} before anything is sent.
     * A user who cannot sign in yet has no login to take; for them the link is the invitation, sent
     * again, and their address may well be unverified — refusing would leave no way to resend it.
     */
    @PreAuthorize(AccountAccess.USER_ADMIN)
    public void sendPasswordReset(UUID accountId) {
        AccountRow row = findRow(accountId);
        if (AccountStatus.of(row.getStatus()) == AccountStatus.CLOSED) {
            throw new AccountClosedException(accountId);
        }
        UUID subject = linkedSubject(row);
        requireEmailDelivery();
        KeycloakUser user = keycloak.findById(subject).orElseThrow(() -> identityNotFound(accountId));
        if (!user.emailVerified() && keycloak.canSignIn(subject)) {
            log.warn("Refused a password reset for account {}: it can sign in and its email address is unverified",
                    accountId);
            throw new EmailNotVerifiedException(accountId);
        }
        sendSetPasswordLink(accountId, subject);
        log.info("Sent account {} a link to set a new password", accountId);
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

        switch (keycloak.update(subject, email, firstName, lastName, null)) {
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
        // The attempt being repeated may have stopped between the insert and the grant, or before
        // its invitation went out. A user who can sign in is past needing one, which is what keeps a
        // replay of a long-finished create from emailing them again. Whether one is owed is decided
        // first, so a realm without SMTP refuses the repeat before it writes anything, the grant
        // included.
        boolean invite = !keycloak.canSignIn(subject);
        if (invite) {
            requireEmailDelivery();
        }
        keycloak.grantRealmRole(subject, END_USER);
        if (invite) {
            sendSetPasswordLink(account.getAccountId(), subject);
        }
        return account;
    }

    /**
     * The invitation, or a password reset: Keycloak's email with a link to set a password. Whether
     * the realm can send email at all is the caller's to check, before it writes anything.
     */
    private void sendSetPasswordLink(UUID accountId, UUID subject) {
        if (!keycloak.sendSetPasswordEmail(subject, emailLinkLifespan)) {
            throw identityNotFound(accountId);
        }
    }

    /**
     * Refuse before writing anything if the realm cannot send email. Without SMTP, Keycloak fails
     * the send with a 500 that reads like an outage, after the user and the account already exist.
     */
    private void requireEmailDelivery() {
        if (!keycloak.emailConfigured()) {
            throw new EmailDeliveryUnavailableException();
        }
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
