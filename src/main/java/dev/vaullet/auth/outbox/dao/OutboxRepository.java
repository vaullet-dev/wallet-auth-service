package dev.vaullet.auth.outbox.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Every statement against {@code identity_schema.outbox}, and nothing else.
 *
 * <p>The SQL lives in {@code db/sql/outbox/}, loaded by {@link OutboxStatements}; the reasoning for
 * each statement is in its file. As in {@code AccountRepository} the methods take no decisions:
 * which event to write, how long a lease or a backoff is, and what a failure means belong to
 * {@code Outbox} and {@code OutboxRelay}.
 *
 * <p><b>No method here is meant to run inside a transaction, except {@link #insert}.</b> Each of
 * the relay's statements is one autocommitted statement, and that is the design: the claim's row
 * locks last as long as the claim does, and no transaction is ever open while the relay waits for
 * the ledger. {@link #insert} is the opposite — it must join the caller's, which {@code Outbox}
 * enforces.
 */
@Repository
public class OutboxRepository {

    private final JdbcTemplate jdbc;
    private final OutboxStatements sql;

    OutboxRepository(JdbcTemplate jdbc, OutboxStatements sql) {
        this.jdbc = jdbc;
        this.sql = sql;
    }

    /**
     * An entry to write: ADR-007's envelope, field for column.
     *
     * @param payload the payload as JSON text, an object
     */
    public record NewEntry(
            UUID eventId,
            String eventType,
            String schemaVersion,
            Instant occurredAt,
            String producerService,
            @Nullable String producerVersion,
            @Nullable String correlationId,
            UUID accountId,
            String payload) {}

    /**
     * An entry a relay has claimed: as much of it as delivering it needs.
     *
     * @param attempts attempts finished before this one
     * @param leaseEnd when the claim's lease ends, by the database's clock — and the token the outcome
     *     must show: {@link #markDelivered} and {@link #markFailed} record nothing once the entry's
     *     {@code next_attempt_at} holds anything else
     */
    public record ClaimedEntry(
            UUID eventId,
            String eventType,
            UUID accountId,
            @Nullable String correlationId,
            int attempts,
            Instant queuedAt,
            Instant leaseEnd) {}

    /**
     * What is waiting.
     *
     * @param oldestQueuedAt when the longest-waiting entry was written; null when nothing waits
     */
    public record Backlog(long pending, @Nullable Instant oldestQueuedAt) {}

    /** Write an entry, in the caller's transaction. */
    public void insert(NewEntry entry) {
        jdbc.update(sql.insert,
                entry.eventId(),
                entry.eventType(),
                entry.schemaVersion(),
                timestamp(entry.occurredAt()),
                entry.producerService(),
                entry.producerVersion(),
                entry.correlationId(),
                entry.accountId(),
                entry.payload());
    }

    /**
     * Claim up to {@code limit} due entries and lease them: none of them is due again, for this
     * relay or any other, until {@code lease} has passed or an outcome is recorded.
     *
     * @return the claimed entries, oldest due first; empty when nothing is due
     */
    public List<ClaimedEntry> claimDue(int limit, Duration lease) {
        return jdbc.query(sql.claimDue, OutboxRepository::mapClaimed, limit, seconds(lease));
    }

    /**
     * Record that the entry is done, if the claim that delivered it still holds its lease.
     *
     * @param leaseEnd the {@link ClaimedEntry#leaseEnd()} of that claim
     * @return false when the lease was lost — it ended and another relay claimed the entry, whose
     *     outcome it now is, or someone changed the entry by hand — and nothing was written
     */
    public boolean markDelivered(UUID eventId, Instant leaseEnd) {
        return jdbc.update(sql.markDelivered, eventId, timestamp(leaseEnd)) == 1;
    }

    /**
     * Record a failed attempt and make the entry due again after {@code retryIn}, if the claim that
     * made the attempt still holds its lease.
     *
     * @param leaseEnd the {@link ClaimedEntry#leaseEnd()} of that claim
     * @return false when the lease was lost, as for {@link #markDelivered}, and nothing was written
     */
    public boolean markFailed(UUID eventId, Instant leaseEnd, Duration retryIn, String error) {
        return jdbc.update(sql.markFailed, seconds(retryIn), error, eventId, timestamp(leaseEnd)) == 1;
    }

    /**
     * Queue an entry of this type for every account that is not {@code CLOSED} and has none: the
     * relay's sweep. One statement, like the relay's others, and safe to run on several replicas at
     * once — see {@code queue-missing.sql}.
     *
     * @param payload the payload as JSON text, an object
     * @return how many accounts it queued
     */
    public int queueMissing(
            String eventType,
            String schemaVersion,
            String producerService,
            @Nullable String producerVersion,
            String payload) {
        return jdbc.update(sql.queueMissing, eventType, schemaVersion, producerService, producerVersion, payload,
                eventType);
    }

    public Backlog backlog() {
        @Nullable Backlog backlog = jdbc.queryForObject(sql.backlog, (rs, rowNum) -> {
            @Nullable OffsetDateTime oldest = rs.getObject("oldest_queued_at", OffsetDateTime.class);
            return new Backlog(rs.getLong("pending"), oldest == null ? null : oldest.toInstant());
        });
        // An aggregate without GROUP BY returns exactly one row, even over no rows at all.
        if (backlog == null) {
            throw new IllegalStateException("SELECT count(*) produced no row");
        }
        return backlog;
    }

    private static ClaimedEntry mapClaimed(ResultSet rs, int rowNum) throws SQLException {
        return new ClaimedEntry(
                rs.getObject("event_id", UUID.class),
                rs.getString("event_type"),
                rs.getObject("account_id", UUID.class),
                rs.getString("correlation_id"),
                rs.getInt("attempts"),
                // OffsetDateTime, never Timestamp: see AccountRepository.instant. For lease_end it
                // matters twice over, because it goes back as a token compared with =: the round
                // trip must give PostgreSQL its own microseconds, unshifted.
                rs.getObject("queued_at", OffsetDateTime.class).toInstant(),
                rs.getObject("lease_end", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** make_interval takes seconds as a double precision, which keeps sub-second leases exact in tests. */
    private static double seconds(Duration duration) {
        return duration.toNanos() / 1_000_000_000.0;
    }
}
