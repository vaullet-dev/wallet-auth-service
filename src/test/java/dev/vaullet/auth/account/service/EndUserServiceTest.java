package dev.vaullet.auth.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.vaullet.auth.account.dao.AccountRepository;
import dev.vaullet.auth.account.dao.AccountRepository.AccountRow;
import dev.vaullet.auth.account.dao.KeycloakUsers;
import dev.vaullet.auth.account.dao.KeycloakUsers.KeycloakUser;
import dev.vaullet.auth.account.dao.KeycloakUsers.NewKeycloakUser;
import dev.vaullet.auth.account.dao.KeycloakUsers.UpdateOutcome;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The end-user rules, without Keycloak or a database.
 *
 * <p>Both collaborators are mocked and the service is real. What is asserted is the part that has
 * to be right when two systems disagree: the order of the writes, whether a 409 from Keycloak is
 * this request's own earlier attempt or somebody else, and when the invitation goes out. {@code KeycloakUsersIT} covers what Keycloak
 * actually does, and {@code EndUserApiIT} the whole path.
 */
@ExtendWith(MockitoExtension.class)
class EndUserServiceTest {

    private static final String REF = "acme-user-8813";
    private static final String USERNAME = "jane.doe";
    private static final String EMAIL = "jane.doe@example.com";
    private static final UUID SUB = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    private static final Duration LINK_LIFESPAN = Duration.ofHours(72);

    @Mock
    private AccountRepository accounts;

    @Mock
    private KeycloakUsers keycloak;

    private EndUserService service;

    @BeforeEach
    void setUp() {
        service = new EndUserService(accounts, keycloak, new AuthProperties(
                AuthProperties.Provider.LOCAL,
                new AuthProperties.Keycloak(null, "vaullet", "wallet-auth-service", null, Duration.ofSeconds(5)),
                new AuthProperties.UserManagement(LINK_LIFESPAN)));
    }

    private static NewEndUser command() {
        return new NewEndUser(REF, USERNAME, EMAIL, "Jane", "Doe", false);
    }

    private static AccountRow row(UUID accountId, @Nullable UUID sub, @Nullable String ref, String status) {
        Instant now = Instant.parse("2026-09-29T00:00:00Z");
        return new AccountRow(accountId, sub, ref, status, now, now);
    }

    /** A Keycloak user; {@code accountId} null means one this service did not create. */
    private static KeycloakUser user(UUID id, String username, String email, @Nullable UUID accountId) {
        Map<String, List<String>> attributes =
                accountId == null ? Map.of() : Map.of("account_id", List.of(accountId.toString()));
        return new KeycloakUser(id, username, email, "Jane", "Doe", false, true, 0L, attributes, List.of());
    }

    /** The same user, with an email address they have proved. */
    private static KeycloakUser verified(KeycloakUser user) {
        return new KeycloakUser(user.id(), user.username(), user.email(), user.firstName(), user.lastName(), true,
                user.enabled(), user.createdTimestamp(), user.attributes(), user.requiredActions());
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("checks email, mints the id, creates the Keycloak user with it, the anchor, the role, then invites")
        void creates_in_order() {
            when(accounts.findByExternalRef(REF)).thenReturn(Optional.empty());
            when(keycloak.emailConfigured()).thenReturn(true);
            when(keycloak.create(any())).thenReturn(Optional.of(SUB));
            when(accounts.insert(any(), eq(SUB), eq(REF), eq("ACTIVE")))
                    .thenAnswer(call -> row(call.getArgument(0), SUB, REF, "ACTIVE"));
            when(keycloak.sendSetPasswordEmail(SUB, LINK_LIFESPAN)).thenReturn(true);

            Account created = service.create(command());

            // The account_id Keycloak holds and the anchor's primary key are one value, and no
            // password goes with it: the invitation is how the user gets one.
            ArgumentCaptor<NewKeycloakUser> sent = ArgumentCaptor.forClass(NewKeycloakUser.class);
            verify(keycloak).create(sent.capture());
            assertThat(sent.getValue().accountId()).isEqualTo(created.getAccountId());
            assertThat(sent.getValue().temporaryPassword()).isNull();
            assertThat(sent.getValue().staff()).isFalse();

            InOrder order = inOrder(keycloak, accounts);
            order.verify(keycloak).requireAccountIdEditableByAdminsOnly();
            order.verify(keycloak).emailConfigured();
            order.verify(keycloak).create(any());
            order.verify(accounts).insert(any(), eq(SUB), eq(REF), eq("ACTIVE"));
            order.verify(keycloak).grantRealmRole(SUB, "END_USER");
            order.verify(keycloak).sendSetPasswordEmail(SUB, LINK_LIFESPAN);
        }

        @Test
        @DisplayName("refuses before writing anything when the realm cannot send email")
        void no_email_delivery() {
            when(accounts.findByExternalRef(REF)).thenReturn(Optional.empty());
            when(keycloak.emailConfigured()).thenReturn(false);

            assertThatThrownBy(() -> service.create(command())).isInstanceOf(EmailDeliveryUnavailableException.class);
            verify(keycloak, never()).create(any());
            verify(accounts, never()).insert(any(), any(), any(), any());
        }

        @Test
        @DisplayName("a repeated request finds its account by reference and creates nothing")
        void repeat_by_reference() {
            when(accounts.findByExternalRef(REF)).thenReturn(Optional.of(row(ACCOUNT, SUB, REF, "ACTIVE")));
            when(keycloak.findById(SUB)).thenReturn(Optional.of(user(SUB, USERNAME, EMAIL, ACCOUNT)));

            when(keycloak.canSignIn(SUB)).thenReturn(true);

            Account replayed = service.create(command());

            assertThat(replayed.getAccountId()).isEqualTo(ACCOUNT);
            verify(keycloak, never()).create(any());
            verify(accounts, never()).insert(any(), any(), any(), any());
            // Granted again, in case the attempt being repeated stopped before its grant.
            verify(keycloak).grantRealmRole(SUB, "END_USER");
            // The user can sign in already, so a replay emails nobody.
            verify(keycloak, never()).sendSetPasswordEmail(any(), any());
        }

        @Test
        @DisplayName("a repeated request sends the invitation again while the user cannot sign in")
        void repeat_resends_the_invitation() {
            when(accounts.findByExternalRef(REF)).thenReturn(Optional.of(row(ACCOUNT, SUB, REF, "ACTIVE")));
            when(keycloak.findById(SUB)).thenReturn(Optional.of(user(SUB, USERNAME, EMAIL, ACCOUNT)));
            when(keycloak.canSignIn(SUB)).thenReturn(false);
            when(keycloak.emailConfigured()).thenReturn(true);
            when(keycloak.sendSetPasswordEmail(SUB, LINK_LIFESPAN)).thenReturn(true);

            service.create(command());

            verify(keycloak).sendSetPasswordEmail(SUB, LINK_LIFESPAN);
        }

        @Test
        @DisplayName("a repeat that owes an invitation checks email delivery before it writes anything")
        void repeat_without_email_delivery() {
            when(accounts.findByExternalRef(REF)).thenReturn(Optional.of(row(ACCOUNT, SUB, REF, "ACTIVE")));
            when(keycloak.findById(SUB)).thenReturn(Optional.of(user(SUB, USERNAME, EMAIL, ACCOUNT)));
            when(keycloak.canSignIn(SUB)).thenReturn(false);
            when(keycloak.emailConfigured()).thenReturn(false);

            assertThatThrownBy(() -> service.create(command())).isInstanceOf(EmailDeliveryUnavailableException.class);
            verify(keycloak, never()).grantRealmRole(any(), any());
        }

        @Test
        @DisplayName("refuses a reference whose account belongs to a different username")
        void reference_taken_by_another_user() {
            when(accounts.findByExternalRef(REF)).thenReturn(Optional.of(row(ACCOUNT, SUB, REF, "ACTIVE")));
            when(keycloak.findById(SUB)).thenReturn(Optional.of(user(SUB, "someone.else", EMAIL, ACCOUNT)));

            assertThatThrownBy(() -> service.create(command())).isInstanceOf(ExternalRefTakenException.class);
            verify(keycloak, never()).create(any());
        }

        @Test
        @DisplayName("refuses a reference held by an account created without an identity")
        void reference_on_an_unlinked_anchor() {
            when(accounts.findByExternalRef(REF)).thenReturn(Optional.of(row(ACCOUNT, null, REF, "ACTIVE")));

            assertThatThrownBy(() -> service.create(command())).isInstanceOf(ExternalRefTakenException.class);
            verifyNoInteractions(keycloak);
        }

        @Test
        @DisplayName("finishes an interrupted create with the account id Keycloak already holds")
        void finishes_interrupted_create() {
            UUID earlierAccountId = UUID.fromString("dddddddd-0000-0000-0000-000000000001");
            when(accounts.findByExternalRef(REF)).thenReturn(Optional.empty());
            when(keycloak.emailConfigured()).thenReturn(true);
            when(keycloak.create(any())).thenReturn(Optional.empty());
            when(keycloak.findByUsername(USERNAME))
                    .thenReturn(Optional.of(user(SUB, USERNAME, EMAIL, earlierAccountId)));
            when(accounts.findByKeycloakSub(SUB)).thenReturn(Optional.empty());
            when(accounts.insert(earlierAccountId, SUB, REF, "ACTIVE"))
                    .thenReturn(row(earlierAccountId, SUB, REF, "ACTIVE"));
            when(keycloak.sendSetPasswordEmail(SUB, LINK_LIFESPAN)).thenReturn(true);

            Account finished = service.create(command());

            // The id the first attempt wrote into Keycloak, not a new one: tokens already carry it.
            assertThat(finished.getAccountId()).isEqualTo(earlierAccountId);
            verify(keycloak).grantRealmRole(SUB, "END_USER");
            verify(keycloak).sendSetPasswordEmail(SUB, LINK_LIFESPAN);
        }

        @Test
        @DisplayName("refuses when the user already has an account under another reference")
        void identity_linked_under_another_reference() {
            when(accounts.findByExternalRef(REF)).thenReturn(Optional.empty());
            when(keycloak.emailConfigured()).thenReturn(true);
            when(keycloak.create(any())).thenReturn(Optional.empty());
            when(keycloak.findByUsername(USERNAME)).thenReturn(Optional.of(user(SUB, USERNAME, EMAIL, ACCOUNT)));
            when(accounts.findByKeycloakSub(SUB)).thenReturn(Optional.of(row(ACCOUNT, SUB, "old-ref", "ACTIVE")));

            assertThatThrownBy(() -> service.create(command())).isInstanceOf(IdentityAlreadyLinkedException.class);
            verify(accounts, never()).insert(any(), any(), any(), any());
        }

        @Test
        @DisplayName("refuses a username that belongs to a user this service did not create")
        void username_taken() {
            when(accounts.findByExternalRef(REF)).thenReturn(Optional.empty());
            when(keycloak.emailConfigured()).thenReturn(true);
            when(keycloak.create(any())).thenReturn(Optional.empty());
            when(keycloak.findByUsername(USERNAME)).thenReturn(Optional.of(user(SUB, USERNAME, EMAIL, null)));

            assertThatThrownBy(() -> service.create(command())).isInstanceOf(UsernameTakenException.class);
            verify(accounts, never()).insert(any(), any(), any(), any());
        }

        @Test
        @DisplayName("refuses a different person whose username matches an interrupted create")
        void username_of_an_interrupted_create_with_another_email() {
            when(accounts.findByExternalRef(REF)).thenReturn(Optional.empty());
            when(keycloak.emailConfigured()).thenReturn(true);
            when(keycloak.create(any())).thenReturn(Optional.empty());
            when(keycloak.findByUsername(USERNAME))
                    .thenReturn(Optional.of(user(SUB, USERNAME, "someone.else@example.com", ACCOUNT)));

            assertThatThrownBy(() -> service.create(command())).isInstanceOf(UsernameTakenException.class);
            verify(accounts, never()).insert(any(), any(), any(), any());
        }

        @Test
        @DisplayName("a 409 with the username free means the email address is taken")
        void email_taken() {
            when(accounts.findByExternalRef(REF)).thenReturn(Optional.empty());
            when(keycloak.emailConfigured()).thenReturn(true);
            when(keycloak.create(any())).thenReturn(Optional.empty());
            when(keycloak.findByUsername(USERNAME)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(command())).isInstanceOf(EmailTakenException.class);
        }

        @Test
        @DisplayName("refuses a retry that resolves to a closed account, and grants nothing")
        void closed_account() {
            when(accounts.findByExternalRef(REF)).thenReturn(Optional.of(row(ACCOUNT, SUB, REF, "CLOSED")));
            when(keycloak.findById(SUB)).thenReturn(Optional.of(user(SUB, USERNAME, EMAIL, ACCOUNT)));

            assertThatThrownBy(() -> service.create(command())).isInstanceOf(AccountClosedException.class);
            verify(keycloak, never()).grantRealmRole(any(), any());
        }
    }

    @Nested
    @DisplayName("updateIdentity")
    class UpdateIdentity {

        @Test
        @DisplayName("refuses a closed account without calling Keycloak")
        void closed_account() {
            when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(row(ACCOUNT, SUB, REF, "CLOSED")));

            assertThatThrownBy(() -> service.updateIdentity(ACCOUNT, new IdentityChanges("new@example.com", null, null)))
                    .isInstanceOf(AccountClosedException.class);
            verifyNoInteractions(keycloak);
        }

        @Test
        @DisplayName("does not send an unchanged email address, so it keeps its verification")
        void unchanged_email_is_not_sent() {
            when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(row(ACCOUNT, SUB, REF, "ACTIVE")));
            when(keycloak.findById(SUB)).thenReturn(Optional.of(user(SUB, USERNAME, EMAIL, ACCOUNT)));
            when(keycloak.update(SUB, null, "Janet", null, null)).thenReturn(UpdateOutcome.UPDATED);

            service.updateIdentity(ACCOUNT, new IdentityChanges(EMAIL.toUpperCase(Locale.ROOT), "Janet", null));

            verify(keycloak).update(SUB, null, "Janet", null, null);
        }

        @Test
        @DisplayName("an address another user already has is EMAIL_TAKEN")
        void email_taken() {
            when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(row(ACCOUNT, SUB, REF, "ACTIVE")));
            when(keycloak.findById(SUB)).thenReturn(Optional.of(user(SUB, USERNAME, EMAIL, ACCOUNT)));
            when(keycloak.update(SUB, "taken@example.com", null, null, null)).thenReturn(UpdateOutcome.EMAIL_TAKEN);

            assertThatThrownBy(() -> service.updateIdentity(ACCOUNT, new IdentityChanges("taken@example.com", null, null)))
                    .isInstanceOf(EmailTakenException.class);
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("closes the account first, then deletes the Keycloak user")
        void closes_then_deletes() {
            when(accounts.updateStatusIfOpen(ACCOUNT, "CLOSED")).thenReturn(Optional.of(row(ACCOUNT, SUB, REF, "CLOSED")));
            when(keycloak.delete(SUB)).thenReturn(true);

            service.delete(ACCOUNT);

            InOrder order = inOrder(accounts, keycloak);
            order.verify(accounts).updateStatusIfOpen(ACCOUNT, "CLOSED");
            order.verify(keycloak).delete(SUB);
        }

        @Test
        @DisplayName("repeated on a closed account, it finishes the deletion instead of failing")
        void repeat_on_a_closed_account() {
            when(accounts.updateStatusIfOpen(ACCOUNT, "CLOSED")).thenReturn(Optional.empty());
            when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(row(ACCOUNT, SUB, REF, "CLOSED")));
            when(keycloak.delete(SUB)).thenReturn(false);

            service.delete(ACCOUNT);

            verify(keycloak).delete(SUB);
        }

        @Test
        @DisplayName("404s for an account that does not exist, and leaves Keycloak alone")
        void unknown_account() {
            when(accounts.updateStatusIfOpen(ACCOUNT, "CLOSED")).thenReturn(Optional.empty());
            when(accounts.findById(ACCOUNT)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete(ACCOUNT)).isInstanceOf(ResourceNotFoundException.class);
            verifyNoInteractions(keycloak);
        }
    }

    @Nested
    @DisplayName("findIdentity")
    class FindIdentity {

        @Test
        @DisplayName("404s for an account created without an identity")
        void unlinked_anchor() {
            when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(row(ACCOUNT, null, REF, "ACTIVE")));

            assertThatThrownBy(() -> service.findIdentity(ACCOUNT)).isInstanceOf(ResourceNotFoundException.class);
            verifyNoInteractions(keycloak);
        }
    }

    @Nested
    @DisplayName("sendPasswordReset")
    class SendPasswordReset {

        @Test
        @DisplayName("emails the link to a verified address, with the configured lifespan")
        void sends() {
            when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(row(ACCOUNT, SUB, REF, "SUSPENDED")));
            when(keycloak.emailConfigured()).thenReturn(true);
            when(keycloak.findById(SUB)).thenReturn(Optional.of(verified(user(SUB, USERNAME, EMAIL, ACCOUNT))));
            when(keycloak.sendSetPasswordEmail(SUB, LINK_LIFESPAN)).thenReturn(true);

            service.sendPasswordReset(ACCOUNT);

            verify(keycloak).sendSetPasswordEmail(SUB, LINK_LIFESPAN);
        }

        @Test
        @DisplayName("refuses an unverified address once the user can sign in: the link would hand over the account")
        void unverified_address_of_a_user_who_can_sign_in() {
            when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(row(ACCOUNT, SUB, REF, "ACTIVE")));
            when(keycloak.emailConfigured()).thenReturn(true);
            when(keycloak.findById(SUB)).thenReturn(Optional.of(user(SUB, USERNAME, EMAIL, ACCOUNT)));
            when(keycloak.canSignIn(SUB)).thenReturn(true);

            assertThatThrownBy(() -> service.sendPasswordReset(ACCOUNT)).isInstanceOf(EmailNotVerifiedException.class);
            verify(keycloak, never()).sendSetPasswordEmail(any(), any());
        }

        @Test
        @DisplayName("sends to an unverified address while the user cannot sign in: that is the invitation again")
        void unverified_address_of_an_invited_user() {
            when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(row(ACCOUNT, SUB, REF, "ACTIVE")));
            when(keycloak.emailConfigured()).thenReturn(true);
            when(keycloak.findById(SUB)).thenReturn(Optional.of(user(SUB, USERNAME, EMAIL, ACCOUNT)));
            when(keycloak.canSignIn(SUB)).thenReturn(false);
            when(keycloak.sendSetPasswordEmail(SUB, LINK_LIFESPAN)).thenReturn(true);

            service.sendPasswordReset(ACCOUNT);

            verify(keycloak).sendSetPasswordEmail(SUB, LINK_LIFESPAN);
        }

        @Test
        @DisplayName("refuses a closed account without calling Keycloak")
        void closed_account() {
            when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(row(ACCOUNT, SUB, REF, "CLOSED")));

            assertThatThrownBy(() -> service.sendPasswordReset(ACCOUNT)).isInstanceOf(AccountClosedException.class);
            verifyNoInteractions(keycloak);
        }

        @Test
        @DisplayName("404s for an account with no identity to email")
        void unlinked_anchor() {
            when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(row(ACCOUNT, null, REF, "ACTIVE")));

            assertThatThrownBy(() -> service.sendPasswordReset(ACCOUNT)).isInstanceOf(ResourceNotFoundException.class);
            verifyNoInteractions(keycloak);
        }

        @Test
        @DisplayName("says so when the realm cannot send email, rather than failing like an outage")
        void no_email_delivery() {
            when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(row(ACCOUNT, SUB, REF, "ACTIVE")));
            when(keycloak.emailConfigured()).thenReturn(false);

            assertThatThrownBy(() -> service.sendPasswordReset(ACCOUNT))
                    .isInstanceOf(EmailDeliveryUnavailableException.class);
            verify(keycloak, never()).sendSetPasswordEmail(any(), any());
        }

        @Test
        @DisplayName("404s when Keycloak no longer has the user")
        void identity_gone() {
            when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(row(ACCOUNT, SUB, REF, "ACTIVE")));
            when(keycloak.emailConfigured()).thenReturn(true);
            when(keycloak.findById(SUB)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.sendPasswordReset(ACCOUNT)).isInstanceOf(ResourceNotFoundException.class);
            verify(keycloak, never()).sendSetPasswordEmail(any(), any());
        }

        @Test
        @DisplayName("404s when the user is deleted between the check and the send")
        void identity_gone_while_sending() {
            when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(row(ACCOUNT, SUB, REF, "ACTIVE")));
            when(keycloak.emailConfigured()).thenReturn(true);
            when(keycloak.findById(SUB)).thenReturn(Optional.of(verified(user(SUB, USERNAME, EMAIL, ACCOUNT))));
            when(keycloak.sendSetPasswordEmail(SUB, LINK_LIFESPAN)).thenReturn(false);

            assertThatThrownBy(() -> service.sendPasswordReset(ACCOUNT)).isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("search")
    class Search {

        private final EndUserQuery everyone = new EndUserQuery(null, null, null);

        /** Users u0..u(n-1) in username order; every third one is staff, with no account. */
        private List<KeycloakUser> users(int from, int count) {
            return java.util.stream.IntStream.range(from, from + count)
                    .mapToObj(i -> user(sub(i), "u" + i, "u" + i + "@example.com", i % 3 == 2 ? null : account(i)))
                    .toList();
        }

        private UUID sub(int i) {
            return new UUID(0xbbbbL, i);
        }

        private UUID account(int i) {
            return new UUID(0xccccL, i);
        }

        /** The anchors of whichever of these users are end users. */
        private void anchorsFor(List<KeycloakUser> batch) {
            List<AccountRow> rows = batch.stream()
                    .filter(user -> user.accountId().isPresent())
                    .map(user -> row(user.accountId().orElseThrow(), user.id(), null, "ACTIVE"))
                    .toList();
            when(accounts.findByKeycloakSubs(batch.stream().map(KeycloakUser::id).toList())).thenReturn(rows);
        }

        @Test
        @DisplayName("keeps end users, skips staff, and on a short list says it has ended")
        void short_list() {
            List<KeycloakUser> all = users(0, 5);
            when(keycloak.search(null, null, null, 0, 100)).thenReturn(all);
            anchorsFor(all);

            Slice<EndUser> page = service.search(everyone, 0, 50);

            assertThat(page.getItems()).extracting(found -> found.getIdentity().getUsername())
                    .containsExactly("u0", "u1", "u3", "u4");
            assertThat(page.getNext()).isEmpty();
        }

        @Test
        @DisplayName("stops at the limit and continues after the last user it returned")
        void filled_page() {
            List<KeycloakUser> batch = users(0, 100);
            when(keycloak.search(null, null, null, 0, 100)).thenReturn(batch);
            anchorsFor(batch);

            Slice<EndUser> page = service.search(everyone, 0, 4);

            // u0, u1, (u2 is staff), u3, u4 — the next page starts at u5, Keycloak's position 5.
            assertThat(page.getItems()).extracting(found -> found.getIdentity().getUsername())
                    .containsExactly("u0", "u1", "u3", "u4");
            assertThat(page.getNext()).contains(5);
        }

        @Test
        @DisplayName("reads on into the next batch to fill a page")
        void reads_on() {
            List<KeycloakUser> first = users(0, 100);
            List<KeycloakUser> second = users(100, 30);
            when(keycloak.search(null, null, null, 0, 100)).thenReturn(first);
            when(keycloak.search(null, null, null, 100, 100)).thenReturn(second);
            anchorsFor(first);
            anchorsFor(second);

            Slice<EndUser> page = service.search(everyone, 0, 80);

            // 67 end users in the first hundred, so 13 more from the second batch: u100 to u118,
            // less the five staff among them. The next page starts right after u118.
            assertThat(page.getItems()).hasSize(80);
            assertThat(page.getItems().getLast().getIdentity().getUsername()).isEqualTo("u118");
            assertThat(page.getNext()).contains(119);
        }

        @Test
        @DisplayName("gives up after five batches with a short page and a cursor, rather than reading on")
        void batch_cap() {
            List<KeycloakUser> staffOnly = java.util.stream.IntStream.range(0, 100)
                    .mapToObj(i -> user(sub(i), "s" + i, "s" + i + "@operator.example", null))
                    .toList();
            when(keycloak.search(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), eq(100)))
                    .thenReturn(staffOnly);
            when(accounts.findByKeycloakSubs(any())).thenReturn(List.of());

            Slice<EndUser> page = service.search(everyone, 0, 50);

            assertThat(page.getItems()).isEmpty();
            assertThat(page.getNext()).contains(500);
            verify(keycloak, org.mockito.Mockito.times(5)).search(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), eq(100));
        }

        @Test
        @DisplayName("passes the filters to Keycloak and starts where the cursor said")
        void filters_and_position() {
            when(keycloak.search(null, "jane@example.com", "jane", 40, 100)).thenReturn(List.of());

            Slice<EndUser> page = service.search(new EndUserQuery(null, "jane@example.com", "jane"), 40, 20);

            assertThat(page.getItems()).isEmpty();
            assertThat(page.getNext()).isEmpty();
        }
    }
}
