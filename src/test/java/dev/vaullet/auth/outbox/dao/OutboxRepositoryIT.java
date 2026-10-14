package dev.vaullet.auth.outbox.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vaullet.auth.account.dao.AccountRepository;
import dev.vaullet.auth.account.dao.AccountRepository.AccountRow;
import dev.vaullet.auth.outbox.dao.OutboxRepository.Backlog;
import dev.vaullet.auth.outbox.dao.OutboxRepository.ClaimedEntry;
import dev.vaullet.auth.outbox.dao.OutboxRepository.NewEntry;
import dev.vaullet.common.test.IntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The outbox's statements against PostgreSQL: what the claim takes, what the lease keeps away,
 * what each outcome writes, and what the sweep finds.
 *
 * <p>The claim's guarantees are PostgreSQL's — {@code FOR UPDATE SKIP LOCKED} and the re-check
 * {@code READ COMMITTED} makes of a row another transaction changed — so they can only be shown
 * against it. {@code OutboxRelayIT} shows the same guarantees from two relays at once.
 *
 * <p>Not transactional, like {@code AccountRepositoryIT}: every statement here is meant to commit
 * on its own, and the locking test needs a second transaction that really holds its locks.
 */
@IntegrationTest
class OutboxRepositoryIT {

    private static final Duration LEASE = Duration.ofMinutes(1);

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void clean() {
        // Accounts too: the sweep reads every account, and other tests leave theirs behind.
        jdbc.execute("TRUNCATE identity_schema.outbox, identity_schema.accounts");
    }

    /** An account and its entry, as the services write them, written directly: this is about the statements. */
    private NewEntry queued() {
        UUID accountId = UUID.randomUUID();
        AccountRow account = accounts.insert(accountId, null, "acme-" + accountId, "ACTIVE");
        NewEntry entry = entry(accountId, account.getCreatedAt());
        outbox.insert(entry);
        return entry;
    }

    private static NewEntry entry(UUID accountId, Instant createdAt) {
        return new NewEntry(UUID.randomUUID(), "identity.account-created", "1.0.0", createdAt, "auth-service",
                "0.0.0-TEST", null, accountId, "{}");
    }

    private void dueSince(NewEntry entry, String interval) {
        jdbc.update("UPDATE identity_schema.outbox SET next_attempt_at = now() - ?::interval WHERE event_id = ?",
                interval, entry.eventId());
    }

    private Map<String, Object> row(NewEntry entry) {
        return jdbc.queryForMap("""
                SELECT attempts, last_error, delivered_at, last_attempt_at,
                       extract(epoch FROM next_attempt_at - now()) AS due_in_seconds
                  FROM identity_schema.outbox WHERE event_id = ?""", entry.eventId());
    }

    private List<UUID> claimed(int limit) {
        return outbox.claimDue(limit, LEASE).stream().map(ClaimedEntry::eventId).toList();
    }

    /** Claim what is due, as a relay does, and return this entry's claim. */
    private ClaimedEntry claim(NewEntry entry, Duration lease) {
        return outbox.claimDue(10, lease).stream()
                .filter(claimed -> claimed.eventId().equals(entry.eventId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("not claimed: " + entry.eventId()));
    }

    private OffsetDateTime nextAttemptAt(NewEntry entry) {
        return jdbc.queryForObject("SELECT next_attempt_at FROM identity_schema.outbox WHERE event_id = ?",
                OffsetDateTime.class, entry.eventId());
    }

    @Test
    @DisplayName("an entry is due the moment it is written, and claimed with what delivering it needs")
    void a_new_entry_is_due() {
        NewEntry entry = queued();

        List<ClaimedEntry> claimed = outbox.claimDue(10, LEASE);

        assertThat(claimed).singleElement().satisfies(one -> {
            assertThat(one.eventId()).isEqualTo(entry.eventId());
            assertThat(one.accountId()).isEqualTo(entry.accountId());
            assertThat(one.eventType()).isEqualTo("identity.account-created");
            assertThat(one.attempts()).isZero();
            // The lease end, to the microsecond PostgreSQL stored: the token an outcome must show.
            assertThat(one.leaseEnd()).isEqualTo(nextAttemptAt(entry).toInstant());
        });
    }

    @Test
    @DisplayName("the lease keeps a claimed entry from the next claim, and returns it when it ends")
    void the_lease() {
        NewEntry entry = queued();

        assertThat(claimed(10)).containsExactly(entry.eventId());
        // A lease ahead, by the database's clock.
        assertThat(((Number) row(entry).get("due_in_seconds")).doubleValue()).isBetween(55.0, 60.5);
        // Neither another replica nor this one's next round sees it while it is being delivered.
        assertThat(claimed(10)).isEmpty();

        // A relay that died mid-delivery never records an outcome: the lease simply ends.
        dueSince(entry, "1 second");
        assertThat(claimed(10)).containsExactly(entry.eventId());
    }

    @Test
    @DisplayName("a claim skips an entry another transaction has locked, rather than waiting for it")
    void skips_locked_rows() throws Exception {
        NewEntry locked = queued();
        NewEntry free = queued();

        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement lock = other.prepareStatement(
                    "SELECT 1 FROM identity_schema.outbox WHERE event_id = ? FOR UPDATE")) {
                lock.setObject(1, locked.eventId());
                lock.executeQuery().close();
            }

            // Waiting would end in the 3-second statement timeout; SKIP LOCKED answers at once.
            assertThat(claimed(10)).containsExactly(free.eventId());

            other.rollback();
        }
        assertThat(claimed(10)).containsExactly(locked.eventId());
    }

    @Test
    @DisplayName("a claim takes due entries only, oldest due first, and no more than asked")
    void due_oldest_first_bounded() {
        NewEntry oldest = queued();
        NewEntry middle = queued();
        NewEntry newest = queued();
        NewEntry later = queued();
        dueSince(oldest, "3 minutes");
        dueSince(middle, "2 minutes");
        dueSince(newest, "1 minute");
        jdbc.update("UPDATE identity_schema.outbox SET next_attempt_at = now() + interval '1 hour' WHERE event_id = ?",
                later.eventId());

        assertThat(claimed(2)).containsExactly(oldest.eventId(), middle.eventId());
        assertThat(claimed(10)).containsExactly(newest.eventId());
    }

    @Test
    @DisplayName("a delivered entry is never claimed again")
    void delivered_is_final() {
        NewEntry entry = queued();
        ClaimedEntry claimed = claim(entry, LEASE);

        assertThat(outbox.markDelivered(entry.eventId(), claimed.leaseEnd())).isTrue();
        dueSince(entry, "1 hour");

        assertThat(claimed(10)).isEmpty();
        Map<String, Object> row = row(entry);
        assertThat(row.get("delivered_at")).isNotNull();
        assertThat(row.get("last_attempt_at")).isNotNull();
        assertThat(row).containsEntry("attempts", 1);
    }

    @Test
    @DisplayName("a failed attempt is counted, explained, and due again after the backoff")
    void a_failed_attempt() {
        NewEntry entry = queued();
        ClaimedEntry claimed = claim(entry, LEASE);

        assertThat(outbox.markFailed(entry.eventId(), claimed.leaseEnd(), Duration.ofSeconds(80), "HTTP 503 ACCOUNT_BUSY"))
                .isTrue();

        Map<String, Object> row = row(entry);
        assertThat(row).containsEntry("attempts", 1).containsEntry("last_error", "HTTP 503 ACCOUNT_BUSY");
        assertThat(row.get("delivered_at")).isNull();
        assertThat(((Number) row.get("due_in_seconds")).doubleValue()).isBetween(75.0, 80.5);
        assertThat(claimed(10)).isEmpty();
    }

    @Test
    @DisplayName("a claim records one outcome: the first moves the lease, and a second finds nothing to change")
    void one_outcome_per_claim() {
        NewEntry entry = queued();
        ClaimedEntry first = claim(entry, LEASE);
        assertThat(outbox.markFailed(entry.eventId(), first.leaseEnd(), Duration.ofSeconds(10), "first try failed"))
                .isTrue();

        assertThat(outbox.markFailed(entry.eventId(), first.leaseEnd(), Duration.ofSeconds(10), "again")).isFalse();
        assertThat(outbox.markDelivered(entry.eventId(), first.leaseEnd())).isFalse();

        dueSince(entry, "1 second");
        ClaimedEntry second = claim(entry, LEASE);
        assertThat(outbox.markDelivered(entry.eventId(), second.leaseEnd())).isTrue();
        assertThat(outbox.markDelivered(entry.eventId(), second.leaseEnd())).isFalse();
        assertThat(outbox.markFailed(entry.eventId(), second.leaseEnd(), Duration.ofSeconds(10), "late")).isFalse();

        // Two attempts, and the error that explains why it took two.
        assertThat(row(entry)).containsEntry("attempts", 2).containsEntry("last_error", "first try failed");
    }

    @Test
    @DisplayName("an outcome from a relay whose lease ran out changes nothing under the relay that claimed the entry next")
    void a_late_outcome_is_fenced_off() throws InterruptedException {
        NewEntry entry = queued();

        // Relay A claims the entry, and stalls until its lease has run out.
        ClaimedEntry a = claim(entry, Duration.ofMillis(200));
        Thread.sleep(400);

        // Relay B finds it due again, claims it, and starts delivering.
        ClaimedEntry b = claim(entry, LEASE);
        assertThat(b.leaseEnd()).isAfter(a.leaseEnd());

        // A's failure comes back now. Unfenced, it would count the attempt and make the entry due at
        // once — a backoff of zero, to make that immediate — under B's delivery.
        assertThat(outbox.markFailed(entry.eventId(), a.leaseEnd(), Duration.ZERO, "A's late failure")).isFalse();
        Map<String, Object> row = row(entry);
        assertThat(row).containsEntry("attempts", 0);
        assertThat(row.get("last_error")).isNull();
        assertThat(nextAttemptAt(entry).toInstant()).isEqualTo(b.leaseEnd());

        // So relay C, claiming now, finds nothing: B's lease holds, and B alone delivers.
        assertThat(claimed(10)).isEmpty();

        // A late success is fenced off the same way...
        assertThat(outbox.markDelivered(entry.eventId(), a.leaseEnd())).isFalse();
        assertThat(row(entry).get("delivered_at")).isNull();

        // ...and B's outcome, under its own lease, is the one recorded.
        assertThat(outbox.markDelivered(entry.eventId(), b.leaseEnd())).isTrue();
        assertThat(row(entry)).containsEntry("attempts", 1);
    }

    @Test
    @DisplayName("the backlog counts what waits, and since when the oldest has")
    void backlog() {
        assertThat(outbox.backlog()).isEqualTo(new Backlog(0, null));

        NewEntry first = queued();
        queued();
        NewEntry delivered = queued();
        assertThat(outbox.markDelivered(delivered.eventId(), claim(delivered, LEASE).leaseEnd())).isTrue();

        Backlog backlog = outbox.backlog();
        assertThat(backlog.pending()).isEqualTo(2);
        OffsetDateTime firstQueued = jdbc.queryForObject(
                "SELECT queued_at FROM identity_schema.outbox WHERE event_id = ?", OffsetDateTime.class, first.eventId());
        assertThat(firstQueued).isNotNull();
        assertThat(backlog.oldestQueuedAt()).isEqualTo(firstQueued.toInstant());
    }

    // --- The sweep ----------------------------------------------------------------------------------

    /** What the previous version leaves behind during a rollout: an account, and no entry. */
    private UUID accountWithoutEntry(String status) {
        UUID accountId = UUID.randomUUID();
        accounts.insert(accountId, null, "acme-" + accountId, status);
        return accountId;
    }

    private int sweep() {
        return outbox.queueMissing("identity.account-created", "1.0.0", "auth-service", "0.0.0-TEST", "{}");
    }

    private int entriesOf(UUID accountId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM identity_schema.outbox WHERE account_id = ?", Integer.class, accountId);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("the sweep queues every account that is not CLOSED and has no entry — once")
    void sweep_queues_what_is_missing() {
        NewEntry queuedWithItsAccount = queued();
        UUID active = accountWithoutEntry("ACTIVE");
        UUID suspended = accountWithoutEntry("SUSPENDED");
        UUID closed = accountWithoutEntry("CLOSED");

        assertThat(sweep()).isEqualTo(2);
        // Nothing left to find: the next sweep, and every one after it, writes nothing.
        assertThat(sweep()).isZero();

        assertThat(jdbc.queryForList("SELECT account_id FROM identity_schema.outbox", UUID.class))
                .containsExactlyInAnyOrder(queuedWithItsAccount.accountId(), active, suspended);
        assertThat(entriesOf(closed)).isZero();

        // The same envelope as any other entry: occurring when the account was created, due now.
        Map<String, Object> swept = jdbc.queryForMap("""
                SELECT o.event_type, o.schema_version, o.producer_service, o.producer_version, o.correlation_id,
                       o.payload::text AS payload, o.attempts,
                       o.occurred_at = a.created_at AS occurred_at_creation, o.next_attempt_at <= now() AS due
                  FROM identity_schema.outbox o JOIN identity_schema.accounts a USING (account_id)
                 WHERE o.account_id = ?""", active);
        assertThat(swept)
                .containsEntry("event_type", "identity.account-created")
                .containsEntry("schema_version", "1.0.0")
                .containsEntry("producer_service", "auth-service")
                .containsEntry("producer_version", "0.0.0-TEST")
                .containsEntry("payload", "{}")
                .containsEntry("attempts", 0)
                .containsEntry("occurred_at_creation", true)
                .containsEntry("due", true);
        assertThat(swept.get("correlation_id")).isNull();
    }

    @Test
    @DisplayName("two sweeps at once — two replicas starting together — queue each account once between them")
    void concurrent_sweeps() throws Exception {
        int missing = 300;
        for (int i = 0; i < missing; i++) {
            accountWithoutEntry("ACTIVE");
        }
        CyclicBarrier start = new CyclicBarrier(2);

        int total;
        try (ExecutorService replicas = Executors.newFixedThreadPool(2)) {
            Callable<Integer> replica = () -> {
                start.await();
                return sweep();
            };
            Future<Integer> one = replicas.submit(replica);
            Future<Integer> other = replicas.submit(replica);
            // Neither fails on the other's rows: ON CONFLICT DO NOTHING takes the collision.
            total = one.get(10, TimeUnit.SECONDS) + other.get(10, TimeUnit.SECONDS);
        }

        assertThat(total).isEqualTo(missing);
        Map<String, Object> counts = jdbc.queryForMap(
                "SELECT count(*) AS entries, count(DISTINCT account_id) AS accounts FROM identity_schema.outbox");
        assertThat(counts).containsEntry("entries", (long) missing).containsEntry("accounts", (long) missing);
    }

    @Test
    @DisplayName("a sweep racing another transaction's entry waits for it, and then writes nothing")
    void sweep_racing_a_writer() throws Exception {
        UUID account = accountWithoutEntry("ACTIVE");

        try (Connection creation = dataSource.getConnection()) {
            // The other side: the account's own entry, written and not yet committed.
            creation.setAutoCommit(false);
            try (PreparedStatement insert = creation.prepareStatement("""
                    INSERT INTO identity_schema.outbox
                           (event_id, event_type, schema_version, occurred_at, producer_service, account_id, payload)
                    VALUES (gen_random_uuid(), 'identity.account-created', '1.0.0', now(), 'auth-service', ?, '{}')""")) {
                insert.setObject(1, account);
                insert.executeUpdate();
            }

            try (ExecutorService sweeper = Executors.newSingleThreadExecutor()) {
                Future<Integer> sweep = sweeper.submit(this::sweep);
                // The sweep cannot see the uncommitted entry, so it tries to write one — and the
                // unique index makes it wait for the transaction that holds the other.
                Thread.sleep(300);
                assertThat(sweep.isDone()).isFalse();

                creation.commit();
                assertThat(sweep.get(5, TimeUnit.SECONDS)).isZero();
            }
        }
        assertThat(entriesOf(account)).isEqualTo(1);
    }

    // --- What the table refuses ---------------------------------------------------------------------

    @Test
    @DisplayName("an account has one account-created event")
    void one_event_per_account() {
        NewEntry entry = queued();

        assertThatThrownBy(() -> outbox.insert(entry(entry.accountId(), entry.occurredAt())))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("outbox_account_created_uk");
    }

    @Test
    @DisplayName("an event is about an account that exists")
    void needs_its_account() {
        assertThatThrownBy(() -> outbox.insert(entry(UUID.randomUUID(), Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("outbox_account_fk");
    }

    @Test
    @DisplayName("an event type no migration introduced is refused")
    void known_event_types_only() {
        UUID accountId = UUID.randomUUID();
        AccountRow account = accounts.insert(accountId, null, "acme-" + accountId, "ACTIVE");

        assertThatThrownBy(() -> outbox.insert(new NewEntry(UUID.randomUUID(), "identity.account-renamed", "1.0.0",
                        account.getCreatedAt(), "auth-service", null, null, accountId, "{}")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("outbox_event_type_ck");
    }
}
