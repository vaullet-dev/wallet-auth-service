package dev.vaullet.auth.outbox.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vaullet.common.test.IntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V3 as a database that already holds accounts meets it — and as one whose accounts are busy — the
 * privileges the running service gets on the new table, and the demo seed that writes the same rows
 * in SQL.
 *
 * <p>The backfill runs the way {@code WalletsMigrationIT} runs the ledger's: Flyway migrates a schema
 * of this test's own to V2, the test writes V2-era accounts into it, and Flyway takes it on to V3.
 * The application's schema, which every other test uses and which is already at the latest version,
 * is not migrated again — V2's grants name {@code identity_schema} explicitly, and repeating them
 * there changes nothing.
 */
@IntegrationTest
class OutboxMigrationIT {

    private static final String SCHEMA = "outbox_migration_it";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    @AfterEach
    void dropSchema() {
        jdbc.update("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("V3 queues every account that is not CLOSED, as the event it would have had")
    void backfills_open_accounts() {
        migrateTo("2");
        UUID active = account("ACTIVE");
        UUID suspended = account("SUSPENDED");
        UUID closed = account("CLOSED");

        migrateTo("3");

        List<Map<String, Object>> entries = jdbc.queryForList("""
                SELECT o.account_id, o.event_type, o.schema_version, o.producer_service, o.producer_version,
                       o.correlation_id, o.causation_id, o.payload::text AS payload, o.attempts, o.delivered_at,
                       o.occurred_at = a.created_at AS occurred_at_creation,
                       o.queued_at > o.occurred_at AS queued_later,
                       o.next_attempt_at <= now() AS due
                  FROM %1$s.outbox o JOIN %1$s.accounts a USING (account_id)""".formatted(SCHEMA));

        // A suspended account is still somebody's, and will be unfrozen into a working wallet; a
        // closed one will never hold money.
        assertThat(entries).extracting(entry -> entry.get("account_id")).containsExactlyInAnyOrder(active, suspended);
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry)
                    .containsEntry("event_type", "identity.account-created")
                    .containsEntry("schema_version", "1.0.0")
                    .containsEntry("producer_service", "auth-service")
                    .containsEntry("payload", "{}")
                    .containsEntry("attempts", 0)
                    // When the account was created, not when the migration ran...
                    .containsEntry("occurred_at_creation", true)
                    // ...which queued_at records instead, for the age of the backlog.
                    .containsEntry("queued_later", true)
                    .containsEntry("due", true);
            // No build wrote these rows, and no request caused them — nor did any event.
            assertThat(entry.get("producer_version")).isNull();
            assertThat(entry.get("correlation_id")).isNull();
            assertThat(entry.get("causation_id")).isNull();
            assertThat(entry.get("delivered_at")).isNull();
        });
        assertThat(entries).extracting(entry -> entry.get("account_id")).doesNotContain(closed);
    }

    @Test
    @DisplayName("V3 on a database with no accounts queues nothing")
    void migrates_an_empty_database() {
        migrateTo("2");
        migrateTo("3");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + ".outbox", Integer.class)).isZero();
    }

    @Test
    @DisplayName("V3 waits two seconds at most to lock accounts, then rolls back whole, and the next try goes through")
    // The guard if lock_timeout ever stopped applying: the statement timeout is lifted below, so
    // nothing else would end the wait.
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void gives_up_on_a_held_lock() throws SQLException {
        migrateTo("2");

        // An account write of the previous version, still open. It holds accounts in ROW EXCLUSIVE,
        // which the foreign key's SHARE ROW EXCLUSIVE has to wait for.
        try (Connection writer = dataSource.getConnection()) {
            writer.setAutoCommit(false);
            try (Statement insert = writer.createStatement()) {
                insert.execute("INSERT INTO " + SCHEMA + ".accounts (account_id, external_ref, status) "
                        + "VALUES (gen_random_uuid(), 'mid-write', 'ACTIVE')");
            }

            long started = System.nanoTime();
            // As the Job runs it: no statement timeout, so what ends the wait is V3's own.
            assertThatThrownBy(() -> migrateTo("3", "SET statement_timeout = 0"))
                    .hasStackTraceContaining("lock timeout");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofSeconds(2));

            writer.rollback();
        }

        // Nothing of V3 is left — no table, and no history row a retry would trip over...
        assertThat(jdbc.queryForObject("SELECT to_regclass(?) IS NULL", Boolean.class, SCHEMA + ".outbox")).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + ".flyway_schema_history WHERE version = '3'",
                Integer.class)).isZero();

        // ...so the Job's next try, with the lock free, simply migrates.
        migrateTo("3", "SET statement_timeout = 0");
        assertThat(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, SCHEMA + ".outbox")).isTrue();
    }

    @Test
    @DisplayName("auth_app may read, write and update the outbox, through V2's default privileges, and delete nothing")
    void app_role_privileges() {
        // The application's own schema, migrated by the role that ran V2 — which is what makes V2's
        // ALTER DEFAULT PRIVILEGES apply to V3's table without a grant of its own.
        Map<String, Object> privileges = jdbc.queryForMap("""
                SELECT has_table_privilege('auth_app', 'identity_schema.outbox', 'SELECT')   AS can_select,
                       has_table_privilege('auth_app', 'identity_schema.outbox', 'INSERT')   AS can_insert,
                       has_table_privilege('auth_app', 'identity_schema.outbox', 'UPDATE')   AS can_update,
                       has_table_privilege('auth_app', 'identity_schema.outbox', 'DELETE')   AS can_delete,
                       has_table_privilege('auth_app', 'identity_schema.outbox', 'TRUNCATE') AS can_truncate""");

        assertThat(privileges)
                .containsEntry("can_select", true)
                .containsEntry("can_insert", true)
                .containsEntry("can_update", true)
                // Delivered rows stay; retention will be a decision of its own, not a privilege
                // the running service already happens to hold.
                .containsEntry("can_delete", false)
                .containsEntry("can_truncate", false);
    }

    @Test
    @DisplayName("the demo seed, as auth_app, writes its account and that account's event once, however often it runs")
    void demo_seed() throws IOException {
        // The PostSync Job's file, run with the Job's role. Against a real database because the
        // only way to know a privilege suffices is to use it.
        String seed = Files.readString(Path.of("k8s/test-users/seed-test-accounts.sql"), StandardCharsets.UTF_8);
        UUID seeded = UUID.fromString("7e57a11c-0000-4000-8000-000000000001");
        jdbc.update("DELETE FROM identity_schema.outbox WHERE account_id = ?", seeded);
        jdbc.update("DELETE FROM identity_schema.accounts WHERE account_id = ?", seeded);

        for (int sync = 0; sync < 2; sync++) {
            transactions.executeWithoutResult(status -> {
                jdbc.execute("SET LOCAL ROLE auth_app");
                jdbc.execute(seed);
            });
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_schema.accounts WHERE account_id = ?",
                Integer.class, seeded)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM identity_schema.outbox o JOIN identity_schema.accounts a USING (account_id)
                 WHERE o.account_id = ? AND o.event_type = 'identity.account-created'
                   AND o.occurred_at = a.created_at AND o.delivered_at IS NULL""",
                Integer.class, seeded)).isEqualTo(1);

        jdbc.update("DELETE FROM identity_schema.outbox WHERE account_id = ?", seeded);
        jdbc.update("DELETE FROM identity_schema.accounts WHERE account_id = ?", seeded);
    }

    private void migrateTo(String version) {
        flyway(version).load().migrate();
    }

    /** With SQL run on each connection Flyway opens, as the Job's connection-init-sql does. */
    private void migrateTo(String version, String initSql) {
        flyway(version).initSql(initSql).load().migrate();
    }

    private FluentConfiguration flyway(String version) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .locations("classpath:db/migration")
                .target(version);
    }

    /** A V2-era account, created a month before the migration runs. */
    private UUID account(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + SCHEMA + ".accounts (account_id, external_ref, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, now() - interval '30 days', now() - interval '30 days')", id, "acme-" + id, status);
        return id;
    }
}
