package dev.vaullet.auth.outbox.service;

import dev.vaullet.auth.outbox.dao.OutboxRepository;
import dev.vaullet.auth.outbox.dao.OutboxRepository.NewEntry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What goes into the outbox, and the two ways in: {@link #accountCreated}, called inside the
 * transaction that creates the account, and {@link #queueMissingAccountCreated}, the relay's sweep
 * for any account that got here without one.
 *
 * <p><b>The same transaction, enforced rather than remembered.</b> {@link Propagation#MANDATORY}
 * refuses a call made outside a transaction with an {@code IllegalTransactionStateException},
 * before anything is written. Committing the entry on its own would quietly bring back the gap the
 * outbox exists to close: an account without its event, or an event without its account.
 *
 * <p><b>Who calls it</b> is every path that creates an account (#39):
 *
 * <ul>
 *   <li>{@code AccountService.create} — {@code POST /v1/accounts} without an identity;
 *   <li>{@code EndUserService.create} — with one, including the repeat that finishes a create
 *       interrupted between Keycloak and the database;
 *   <li>outside Java, V3's backfill and the demo seed in {@code k8s/test-users}, which write the same
 *       row in SQL.
 * </ul>
 *
 * The account at first sign-in (#24) will be the next, and must call this in the transaction that
 * inserts its anchor; {@code AccountApiIT} and {@code EndUserApiIT} show what to assert.
 *
 * <p><b>The sweep is the backstop, not the way.</b> It finds an account with no entry minutes
 * later, which covers the previous version's accounts during a rollout and any path that forgets;
 * but an account queued by its own transaction is queued at once, and never exists without its
 * event in between.
 *
 * <p><b>Each entry is ADR-007's envelope</b> (§3), even though delivering it over HTTP uses only its
 * account id: when Kafka arrives the relay publishes the row as it stands. {@code correlation_id} is
 * the trace id of the request that created the account — the id its error bodies carry as
 * {@code trace_id}, so one search finds the request and every delivery attempt. {@code causation_id}
 * is left {@code NULL}: a request creates an account, never another event.
 *
 * <p>Also reports the backlog, as {@code auth.outbox.pending} and {@code auth.outbox.oldest.pending}.
 * Here rather than on the relay because the backlog exists whether or not a relay runs: with the
 * relay off it is the one number that keeps growing.
 */
@Service
public class Outbox {

    /** ADR-014 §7's event. Its topic, once there is Kafka, is this plus the schema's major: {@code .v1}. */
    static final String ACCOUNT_CREATED = "identity.account-created";

    /**
     * 1.0.0: the envelope and an empty payload. A field added later is a minor version on the same
     * topic (ADR-007 §4).
     */
    static final String ACCOUNT_CREATED_VERSION = "1.0.0";

    /** ADR-007's {@code producer.service}: {@code spring.application.name}, the name the platform knows us by. */
    static final String PRODUCER = "auth-service";

    /** Empty: account_id and occurred_at, in the envelope, are the whole fact. */
    private static final String ACCOUNT_CREATED_PAYLOAD = "{}";

    private final OutboxRepository entries;
    private final ObjectProvider<Tracer> tracer;
    private final @Nullable String version;

    Outbox(OutboxRepository entries, ObjectProvider<Tracer> tracer, ObjectProvider<BuildProperties> build,
            MeterRegistry meters) {
        this.entries = entries;
        this.tracer = tracer;
        // From META-INF/build-info.properties, which every Maven build writes. An IDE run that
        // skipped it writes no version rather than a wrong one.
        @Nullable BuildProperties info = build.getIfAvailable();
        this.version = info == null ? null : info.getVersion();

        // Read on every scrape rather than kept up to date by the relay, so they stay true on an
        // instance whose relay is off or has stopped — the case they are most needed for.
        Gauge.builder("auth.outbox.pending", entries, repository -> repository.backlog().pending())
                .description("Outbox entries not yet delivered")
                .strongReference(true)
                .register(meters);
        TimeGauge.builder("auth.outbox.oldest.pending", entries, TimeUnit.SECONDS, Outbox::oldestWait)
                .description("How long the oldest undelivered outbox entry has waited; zero when none does")
                .strongReference(true)
                .register(meters);
    }

    /**
     * Queue {@code identity.account-created} for an account just inserted, in the caller's
     * transaction.
     *
     * @param createdAt the account's {@code created_at}, which is when the event occurred
     * @throws org.springframework.transaction.IllegalTransactionStateException called outside a
     *     transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void accountCreated(UUID accountId, Instant createdAt) {
        entries.insert(new NewEntry(
                UUID.randomUUID(),
                ACCOUNT_CREATED,
                ACCOUNT_CREATED_VERSION,
                createdAt,
                PRODUCER,
                version,
                correlationId(),
                accountId,
                ACCOUNT_CREATED_PAYLOAD));
    }

    /**
     * Queue {@code identity.account-created} for every account that ought to have it and has not:
     * not {@code CLOSED}, and no entry. One statement of its own, outside any caller's transaction
     * — a sweep, not part of a creation — with this build as the producer and no correlation id.
     *
     * @return how many accounts it queued
     */
    public int queueMissingAccountCreated() {
        return entries.queueMissing(
                ACCOUNT_CREATED, ACCOUNT_CREATED_VERSION, PRODUCER, version, ACCOUNT_CREATED_PAYLOAD);
    }

    /** The trace this call runs in, if any. Work outside a request has none, and a no-op tracer an empty one. */
    private @Nullable String correlationId() {
        @Nullable Tracer current = tracer.getIfAvailable();
        @Nullable Span span = current == null ? null : current.currentSpan();
        @Nullable String traceId = span == null ? null : span.context().traceId();
        return traceId == null || traceId.isEmpty() ? null : traceId;
    }

    private static double oldestWait(OutboxRepository repository) {
        @Nullable Instant oldest = repository.backlog().oldestQueuedAt();
        return oldest == null ? 0 : Math.max(0, Duration.between(oldest, Instant.now()).toMillis() / 1000.0);
    }
}
