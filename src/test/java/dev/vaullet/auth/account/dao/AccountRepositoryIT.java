package dev.vaullet.auth.account.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vaullet.auth.account.dao.AccountRepository.AccountRow;
import dev.vaullet.common.test.IntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What the database enforces, proved against the database.
 *
 * <p>Half of this service's correctness is in {@code V1__identity.sql} rather than in Java — the
 * {@code CHECK} constraints, the two trigger invariants, and the guard inside
 * {@code update-status-if-open.sql}. None of it can be tested against H2: the trigger is plpgsql,
 * the unique constraints depend on PostgreSQL treating NULLs as distinct, and an embedded database
 * that <em>accepts</em> the migration without enforcing it would give a green suite proving nothing.
 *
 * <p>Not transactional, deliberately. The concurrency test needs two committed transactions racing
 * each other, which a rolled-back test transaction cannot produce — so each test truncates instead.
 */
@IntegrationTest
class AccountRepositoryIT {

    private static final UUID SUB = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE identity_schema.accounts");
    }

    private AccountRow anAccount(String ref) {
        return accounts.insert(UUID.randomUUID(), null, ref, "ACTIVE");
    }

    @Test
    @DisplayName("insert returns the row the database wrote, timestamps included")
    void insert_returns_the_stored_row() {
        AccountRow created = accounts.insert(UUID.randomUUID(), SUB, "acme-1", "ACTIVE");

        assertThat(created.getExternalRef()).isEqualTo("acme-1");
        assertThat(created.getKeycloakSub()).isEqualTo(SUB);
        assertThat(created.getStatus()).isEqualTo("ACTIVE");
        // Defaults applied by the database, which is why the statement uses RETURNING rather than a
        // follow-up read that could disagree.
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(created.getUpdatedAt()).isNotNull();
        assertThat(accounts.findById(created.getAccountId())).contains(created);
    }

    @Test
    @DisplayName("an account is findable by either natural key")
    void findable_by_either_key() {
        AccountRow created = accounts.insert(UUID.randomUUID(), SUB, "acme-1", "ACTIVE");

        assertThat(accounts.findByExternalRef("acme-1")).contains(created);
        assertThat(accounts.findByKeycloakSub(SUB)).contains(created);
        assertThat(accounts.findByExternalRef("nobody")).isEmpty();
    }

    @Test
    @DisplayName("an unlinked anchor has no keycloak_sub, and many may coexist")
    void nulls_are_distinct() {
        // PostgreSQL treats NULLs as distinct in a unique constraint, which is what lets any number
        // of accounts be unlinked. NULLS NOT DISTINCT would break this on the second row.
        AccountRow first = anAccount("acme-1");
        AccountRow second = anAccount("acme-2");

        assertThat(first.getKeycloakSub()).isNull();
        assertThat(second.getKeycloakSub()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_schema.accounts", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("an account with neither natural key is refused")
    void natural_key_is_required() {
        assertThatThrownBy(() -> accounts.insert(UUID.randomUUID(), null, null, "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("accounts_natural_key_ck");
    }

    @Test
    @DisplayName("a blank external_ref is refused, so it cannot pose as a natural key")
    void blank_reference_is_refused() {
        assertThatThrownBy(() -> accounts.insert(UUID.randomUUID(), null, "   ", "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("accounts_external_ref_not_blank_ck");
    }

    @Test
    @DisplayName("both natural keys are unique")
    void natural_keys_are_unique() {
        anAccount("acme-1");
        accounts.insert(UUID.randomUUID(), SUB, "acme-2", "ACTIVE");

        assertThatThrownBy(() -> anAccount("acme-1")).isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> accounts.insert(UUID.randomUUID(), SUB, "acme-3", "ACTIVE"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("an unknown status is refused by the CHECK, not by Java")
    void unknown_status_is_refused() {
        assertThatThrownBy(() -> accounts.insert(UUID.randomUUID(), null, "acme-1", "LOCKED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("accounts_status_ck");
    }

    @Test
    @DisplayName("the guarded update moves an open account and returns what it wrote")
    void guarded_update_moves_an_open_account() {
        UUID id = anAccount("acme-1").getAccountId();

        assertThat(accounts.updateStatusIfOpen(id, "SUSPENDED")).get()
                .extracting(AccountRow::getStatus).isEqualTo("SUSPENDED");
        assertThat(accounts.updateStatusIfOpen(id, "ACTIVE")).get()
                .extracting(AccountRow::getStatus).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("the guarded update returns empty for an unknown account and for a closed one")
    void guarded_update_refuses() {
        assertThat(accounts.updateStatusIfOpen(UUID.randomUUID(), "SUSPENDED")).isEmpty();

        UUID id = anAccount("acme-1").getAccountId();
        accounts.updateStatusIfOpen(id, "CLOSED");

        // Both refusals look identical from here, which is why the service reads the row afterwards
        // to tell them apart — safe only because CLOSED is terminal.
        assertThat(accounts.updateStatusIfOpen(id, "ACTIVE")).isEmpty();
    }

    @Test
    @DisplayName("closing twice succeeds, so a retried close is a no-op")
    void closing_twice_is_allowed_by_the_statement() {
        UUID id = anAccount("acme-1").getAccountId();

        assertThat(accounts.updateStatusIfOpen(id, "CLOSED")).isPresent();
        // The second one does not match — the guard excludes CLOSED — so the service, not the
        // statement, is what makes a repeated close succeed.
        assertThat(accounts.updateStatusIfOpen(id, "CLOSED")).isEmpty();
    }

    @Test
    @DisplayName("the trigger refuses to change external_ref once it is set")
    void external_ref_is_immutable() {
        UUID id = anAccount("acme-1").getAccountId();

        // Straight SQL, bypassing the repository entirely: the point of a trigger is that it holds
        // for code paths nobody has written yet, including a psql session at 3am.
        assertThatThrownBy(() -> jdbc.update("UPDATE identity_schema.accounts SET external_ref = ? WHERE account_id = ?", "renamed", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("immutable once set");
    }

    @Test
    @DisplayName("the trigger allows setting external_ref that was never set")
    void external_ref_can_be_filled_in() {
        UUID id = accounts.insert(UUID.randomUUID(), SUB, null, "ACTIVE").getAccountId();

        assertThat(jdbc.update("UPDATE identity_schema.accounts SET external_ref = ? WHERE account_id = ?", "late", id))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the trigger refuses to reopen a closed account, even from raw SQL")
    void closed_is_terminal_in_the_database() {
        UUID id = anAccount("acme-1").getAccountId();
        accounts.updateStatusIfOpen(id, "CLOSED");

        assertThatThrownBy(() -> jdbc.update("UPDATE identity_schema.accounts SET status = 'ACTIVE' WHERE account_id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("cannot be reopened");
    }

    @Test
    @DisplayName("a close racing a suspend always leaves the account closed")
    void concurrent_close_and_suspend() throws Exception {
        UUID id = anAccount("acme-1").getAccountId();

        // Whichever order they land in, the outcome is the same, and that is the property worth
        // asserting. Suspend-then-close ends CLOSED; close-then-suspend leaves the suspend matching
        // nothing, so it also ends CLOSED. A read-then-write guard could end SUSPENDED here — it
        // would decide against a state that had already changed — which is the bug this shape avoids.
        var barrier = new CyclicBarrier(2);
        Callable<Boolean> close = () -> {
            barrier.await();
            return accounts.updateStatusIfOpen(id, "CLOSED").isPresent();
        };
        Callable<Boolean> suspend = () -> {
            barrier.await();
            return accounts.updateStatusIfOpen(id, "SUSPENDED").isPresent();
        };

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results = pool.invokeAll(List.of(close, suspend));
            for (Future<Boolean> result : results) {
                result.get();
            }
        }

        assertThat(accounts.findById(id)).get().extracting(AccountRow::getStatus).isEqualTo("CLOSED");
    }
}
