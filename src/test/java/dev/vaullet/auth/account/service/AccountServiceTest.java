package dev.vaullet.auth.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vaullet.auth.account.dao.AccountRepository;
import dev.vaullet.auth.account.dao.AccountRepository.AccountRow;
import dev.vaullet.auth.common.error.exception.AccountClosedException;
import dev.vaullet.auth.common.error.exception.ExternalRefTakenException;
import dev.vaullet.auth.common.error.exception.IdentityAlreadyLinkedException;
import dev.vaullet.common.error.ResourceNotFoundException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The rules, without a database.
 *
 * <p>The repository is mocked and the service is real — mocking the thing under test proves nothing.
 * What is being asserted here is the part of {@code create} that decides whether a matched account
 * is a repeat of this request or a collision with a different one, which is pure logic and does not
 * need PostgreSQL to exercise. {@code AccountRepositoryIT} covers what the database enforces;
 * between them there is no rule that only one of the two can see.
 *
 * <p>Bean Validation is deliberately NOT exercised here: {@code @Valid} on a service method is
 * applied by a Spring proxy, which a plain unit test does not build. {@code AccountApiIT} asserts
 * the 400 instead, through the real stack, which is where an integrator would meet it.
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    private static final UUID SUB = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID OTHER_SUB = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
    private static final String REF = "acme-user-8813";

    @Mock
    private AccountRepository accounts;

    @InjectMocks
    private AccountService service;

    private static AccountRow row(@Nullable UUID sub, @Nullable String ref, String status) {
        Instant now = Instant.parse("2026-09-17T00:00:00Z");
        return new AccountRow(UUID.randomUUID(), sub, ref, status, now, now);
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("inserts when no natural key matches")
        void inserts_when_nothing_matches() {
            when(accounts.findByExternalRef(REF)).thenReturn(Optional.empty());
            when(accounts.insert(any(), eq(null), eq(REF), eq("ACTIVE")))
                    .thenReturn(row(null, REF, "ACTIVE"));

            Account created = service.create(new NewAccount(null, REF));

            assertThat(created.getExternalRef()).contains(REF);
            assertThat(created.getStatus()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(created.getKeycloakSub()).isEmpty();
        }

        @Test
        @DisplayName("returns the existing account and inserts nothing when the reference repeats")
        void repeat_is_a_retry() {
            AccountRow stored = row(null, REF, "ACTIVE");
            when(accounts.findByExternalRef(REF)).thenReturn(Optional.of(stored));

            Account result = service.create(new NewAccount(null, REF));

            assertThat(result.getAccountId()).isEqualTo(stored.getAccountId());
            verify(accounts, never()).insert(any(), any(), any(), any());
        }

        @Test
        @DisplayName("omitting a field on a retry is not a contradiction")
        void omitting_a_field_is_not_a_contradiction() {
            when(accounts.findByExternalRef(REF)).thenReturn(Optional.of(row(SUB, REF, "ACTIVE")));

            // The stored account has an identity; this request does not mention one. Sending less
            // than last time is still the same request.
            Account result = service.create(new NewAccount(null, REF));

            assertThat(result.getKeycloakSub()).contains(SUB);
            verify(accounts, never()).insert(any(), any(), any(), any());
        }

        @Test
        @DisplayName("refuses a reference already held behind a different identity")
        void reference_held_by_another_identity() {
            when(accounts.findByExternalRef(REF)).thenReturn(Optional.of(row(OTHER_SUB, REF, "ACTIVE")));

            assertThatThrownBy(() -> service.create(new NewAccount(SUB, REF)))
                    .isInstanceOf(ExternalRefTakenException.class)
                    .hasMessageContaining(REF);
        }

        @Test
        @DisplayName("refuses an identity already linked to another reference")
        void identity_linked_to_another_reference() {
            when(accounts.findByExternalRef("new-ref")).thenReturn(Optional.empty());
            when(accounts.findByKeycloakSub(SUB)).thenReturn(Optional.of(row(SUB, "old-ref", "ACTIVE")));

            assertThatThrownBy(() -> service.create(new NewAccount(SUB, "new-ref")))
                    .isInstanceOf(IdentityAlreadyLinkedException.class)
                    // ADR-006: neither identifier belongs in the message.
                    .hasMessageNotContaining("old-ref")
                    .hasMessageNotContaining(SUB.toString());
        }

        @Test
        @DisplayName("refuses a retry that resolves to a closed account")
        void retry_onto_a_closed_account() {
            when(accounts.findByExternalRef(REF)).thenReturn(Optional.of(row(null, REF, "CLOSED")));

            // A 201 here would tell the caller their account is ready to use. It is not, permanently.
            assertThatThrownBy(() -> service.create(new NewAccount(null, REF)))
                    .isInstanceOf(AccountClosedException.class);
        }

        @Test
        @DisplayName("falls back to the identity when no reference is supplied")
        void finds_by_identity_when_no_reference() {
            when(accounts.findByKeycloakSub(SUB)).thenReturn(Optional.of(row(SUB, null, "ACTIVE")));

            Account result = service.create(new NewAccount(SUB, null));

            assertThat(result.getKeycloakSub()).contains(SUB);
            verify(accounts, never()).findByExternalRef(any());
        }
    }

    @Nested
    @DisplayName("changeStatus")
    class ChangeStatus {

        @Test
        @DisplayName("returns the row the guarded update produced")
        void updates_an_open_account() {
            UUID id = UUID.randomUUID();
            when(accounts.updateStatusIfOpen(id, "SUSPENDED"))
                    .thenReturn(Optional.of(row(null, REF, "SUSPENDED")));

            assertThat(service.changeStatus(id, AccountStatus.SUSPENDED).getStatus())
                    .isEqualTo(AccountStatus.SUSPENDED);
        }

        @Test
        @DisplayName("404s when the guard refused because there is no such account")
        void unknown_account() {
            UUID id = UUID.randomUUID();
            when(accounts.updateStatusIfOpen(id, "SUSPENDED")).thenReturn(Optional.empty());
            when(accounts.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.changeStatus(id, AccountStatus.SUSPENDED))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("409s when the guard refused because the account is closed")
        void closed_account_cannot_be_reopened() {
            UUID id = UUID.randomUUID();
            when(accounts.updateStatusIfOpen(id, "ACTIVE")).thenReturn(Optional.empty());
            when(accounts.findById(id)).thenReturn(Optional.of(row(null, REF, "CLOSED")));

            assertThatThrownBy(() -> service.changeStatus(id, AccountStatus.ACTIVE))
                    .isInstanceOf(AccountClosedException.class);
        }

        @Test
        @DisplayName("closing an already-closed account succeeds, so a retried DELETE is safe")
        void closing_twice_is_idempotent() {
            UUID id = UUID.randomUUID();
            when(accounts.updateStatusIfOpen(id, "CLOSED")).thenReturn(Optional.empty());
            when(accounts.findById(id)).thenReturn(Optional.of(row(null, REF, "CLOSED")));

            assertThat(service.changeStatus(id, AccountStatus.CLOSED).getStatus())
                    .isEqualTo(AccountStatus.CLOSED);
        }
    }
}
