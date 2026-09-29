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
import dev.vaullet.auth.common.error.exception.EmailTakenException;
import dev.vaullet.auth.common.error.exception.ExternalRefTakenException;
import dev.vaullet.auth.common.error.exception.IdentityAlreadyLinkedException;
import dev.vaullet.auth.common.error.exception.UsernameTakenException;
import dev.vaullet.common.error.ResourceNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The end-user rules, without Keycloak or a database.
 *
 * <p>Both collaborators are mocked and the service is real. What is asserted is the part that has
 * to be right when two systems disagree: the order of the writes, and whether a 409 from Keycloak is
 * this request's own earlier attempt or somebody else. {@code KeycloakUsersIT} covers what Keycloak
 * actually does, and {@code EndUserApiIT} the whole path.
 */
@ExtendWith(MockitoExtension.class)
class EndUserServiceTest {

    private static final String REF = "acme-user-8813";
    private static final String USERNAME = "jane.doe";
    private static final String EMAIL = "jane.doe@example.com";
    private static final UUID SUB = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT = UUID.fromString("cccccccc-0000-0000-0000-000000000001");

    @Mock
    private AccountRepository accounts;

    @Mock
    private KeycloakUsers keycloak;

    @InjectMocks
    private EndUserService service;

    private static NewEndUser command() {
        return new NewEndUser(REF, USERNAME, EMAIL, "Jane", "Doe", false, null);
    }

    private static AccountRow row(UUID accountId, @Nullable UUID sub, @Nullable String ref, String status) {
        Instant now = Instant.parse("2026-09-29T00:00:00Z");
        return new AccountRow(accountId, sub, ref, status, now, now);
    }

    /** A Keycloak user; {@code accountId} null means one this service did not create. */
    private static KeycloakUser user(UUID id, String username, String email, @Nullable UUID accountId) {
        Map<String, List<String>> attributes =
                accountId == null ? Map.of() : Map.of("account_id", List.of(accountId.toString()));
        return new KeycloakUser(id, username, email, "Jane", "Doe", false, true, 0L, attributes);
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("mints the id first, creates the Keycloak user with it, then the anchor, then the role")
        void creates_in_order() {
            when(accounts.findByExternalRef(REF)).thenReturn(Optional.empty());
            when(keycloak.create(any())).thenReturn(Optional.of(SUB));
            when(accounts.insert(any(), eq(SUB), eq(REF), eq("ACTIVE")))
                    .thenAnswer(call -> row(call.getArgument(0), SUB, REF, "ACTIVE"));

            Account created = service.create(command());

            // The account_id Keycloak holds and the anchor's primary key are one value.
            ArgumentCaptor<NewKeycloakUser> sent = ArgumentCaptor.forClass(NewKeycloakUser.class);
            verify(keycloak).create(sent.capture());
            assertThat(sent.getValue().accountId()).isEqualTo(created.getAccountId());

            InOrder order = inOrder(keycloak, accounts);
            order.verify(keycloak).requireAccountIdEditableByAdminsOnly();
            order.verify(keycloak).create(any());
            order.verify(accounts).insert(any(), eq(SUB), eq(REF), eq("ACTIVE"));
            order.verify(keycloak).grantRealmRole(SUB, "END_USER");
        }

        @Test
        @DisplayName("a repeated request finds its account by reference and creates nothing")
        void repeat_by_reference() {
            when(accounts.findByExternalRef(REF)).thenReturn(Optional.of(row(ACCOUNT, SUB, REF, "ACTIVE")));
            when(keycloak.findById(SUB)).thenReturn(Optional.of(user(SUB, USERNAME, EMAIL, ACCOUNT)));

            Account replayed = service.create(command());

            assertThat(replayed.getAccountId()).isEqualTo(ACCOUNT);
            verify(keycloak, never()).create(any());
            verify(accounts, never()).insert(any(), any(), any(), any());
            // Granted again, in case the attempt being repeated stopped before its grant.
            verify(keycloak).grantRealmRole(SUB, "END_USER");
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
            when(keycloak.create(any())).thenReturn(Optional.empty());
            when(keycloak.findByUsername(USERNAME))
                    .thenReturn(Optional.of(user(SUB, USERNAME, EMAIL, earlierAccountId)));
            when(accounts.findByKeycloakSub(SUB)).thenReturn(Optional.empty());
            when(accounts.insert(earlierAccountId, SUB, REF, "ACTIVE"))
                    .thenReturn(row(earlierAccountId, SUB, REF, "ACTIVE"));

            Account finished = service.create(command());

            // The id the first attempt wrote into Keycloak, not a new one: tokens already carry it.
            assertThat(finished.getAccountId()).isEqualTo(earlierAccountId);
            verify(keycloak).grantRealmRole(SUB, "END_USER");
        }

        @Test
        @DisplayName("refuses when the user already has an account under another reference")
        void identity_linked_under_another_reference() {
            when(accounts.findByExternalRef(REF)).thenReturn(Optional.empty());
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
            when(keycloak.create(any())).thenReturn(Optional.empty());
            when(keycloak.findByUsername(USERNAME)).thenReturn(Optional.of(user(SUB, USERNAME, EMAIL, null)));

            assertThatThrownBy(() -> service.create(command())).isInstanceOf(UsernameTakenException.class);
            verify(accounts, never()).insert(any(), any(), any(), any());
        }

        @Test
        @DisplayName("refuses a different person whose username matches an interrupted create")
        void username_of_an_interrupted_create_with_another_email() {
            when(accounts.findByExternalRef(REF)).thenReturn(Optional.empty());
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
            when(keycloak.update(SUB, null, "Janet", null)).thenReturn(UpdateOutcome.UPDATED);

            service.updateIdentity(ACCOUNT, new IdentityChanges(EMAIL.toUpperCase(Locale.ROOT), "Janet", null));

            verify(keycloak).update(SUB, null, "Janet", null);
        }

        @Test
        @DisplayName("an address another user already has is EMAIL_TAKEN")
        void email_taken() {
            when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(row(ACCOUNT, SUB, REF, "ACTIVE")));
            when(keycloak.findById(SUB)).thenReturn(Optional.of(user(SUB, USERNAME, EMAIL, ACCOUNT)));
            when(keycloak.update(SUB, "taken@example.com", null, null)).thenReturn(UpdateOutcome.EMAIL_TAKEN);

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
}
