package dev.vaullet.auth.outbox.service;

import dev.vaullet.auth.config.AuthProperties;
import dev.vaullet.auth.outbox.dao.LedgerWallets;
import dev.vaullet.auth.outbox.dao.LedgerWallets.Opening;
import dev.vaullet.auth.outbox.dao.LedgerWallets.WalletType;
import dev.vaullet.auth.outbox.dao.OutboxRepository;
import dev.vaullet.auth.outbox.dao.OutboxRepository.ClaimedEntry;
import dev.vaullet.common.error.exception.UpstreamUnavailableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Delivers the outbox: claims the entries that are due, opens each account's wallets in the ledger,
 * and records how it went.
 *
 * <p><b>Three short steps, and never a transaction open across a call to the ledger.</b>
 *
 * <ol>
 *   <li><b>Claim.</b> One statement selects up to {@code batch-size} due entries
 *       {@code FOR UPDATE SKIP LOCKED} and moves each one's {@code next_attempt_at} a lease ahead.
 *       It commits at once, so its row locks last milliseconds; from then on the lease is what keeps
 *       the entries away from every other relay.
 *   <li><b>Deliver</b>, outside any transaction: {@code POST /v1/wallets} for {@code REAL}, then for
 *       {@code BONUS}.
 *   <li><b>Record</b>, one statement per entry: delivered, or failed and due again after a
 *       {@link Backoff}.
 * </ol>
 *
 * <p><b>Several replicas.</b> Each runs its own rounds against the one table. {@code SKIP LOCKED}
 * keeps two claims made at the same moment off each other's rows, and the lease keeps a claimed
 * entry from every other claim until its outcome is recorded or the lease ends. Within a lease one
 * replica delivers an entry, once. A replica that dies mid-round loses nothing: its entries come
 * due again when their lease ends. A delivery repeated after that would be harmless — the ledger
 * answers the opening of a wallet that exists with that wallet — but the relay does not rely on it.
 *
 * <p><b>The lease outlasts every delivery.</b> A round stops starting deliveries once what is left
 * of the lease is shorter than the longest one could take: two opens, each possibly fetching a
 * token first, each bounded by its connect and read timeouts. The entries it did not reach come due
 * when their lease ends. So no entry is still being delivered by one relay when another may claim
 * it, and the relay refuses to start with a lease too short for even one delivery. The time is
 * {@link System#nanoTime()}'s, which only moves forward: the wall clock can be stepped under a
 * round, and a step back would let one run on past its lease.
 *
 * <p><b>And an outcome is recorded only under the lease it was won under.</b> The timing above is
 * arithmetic on timeouts; a process that stalls — a long pause, a database slow to answer — can
 * still outlive its lease. So each outcome statement also checks that the entry's
 * {@code next_attempt_at} is still the {@link ClaimedEntry#leaseEnd() lease end} its claim set. If
 * another relay has claimed the entry since, the late outcome writes nothing and counts nothing:
 * the entry, its attempts and its new lease are the other relay's.
 *
 * <p><b>An entry is done when both wallets exist.</b> {@code 409 WALLET_CLOSED} counts: the account
 * had the wallet, and someone closed it, which is terminal. That is logged as a warning, because
 * only a person closes a wallet.
 *
 * <p><b>The sweep</b>, {@link #queueMissing()}, is the relay's other job: at start and every
 * {@code sweep-interval}, queue the entry of any account that is not {@code CLOSED} and has none.
 * A release's migration backfills what exists when it runs, but the previous version keeps
 * creating accounts until its last pod stops, and those would otherwise never get their wallets.
 * The sweep catches them, and any path that forgets to queue; it is a backstop, logged as a warning
 * whenever it finds anything.
 *
 * <p><b>Failures come in two kinds</b>, told apart by the log level and the metric's {@code reason}
 * tag only — both are retried the same way, for as long as it takes:
 *
 * <ul>
 *   <li>{@code unavailable}: the ledger or Keycloak did not answer, or answered that it cannot now —
 *       429 or 5xx, {@code 503 ACCOUNT_BUSY} included, and from Keycloak a 408. Expected while the
 *       ledger is not deployed (wallet-ledger-service#12) or restarting, and a warning.
 *   <li>{@code refused}: any other answer that is not the wallet — a 401 or 403, a token Keycloak
 *       would not issue, a token endpoint or a {@code LEDGER_URL} that names something else, a 201
 *       that is not the wallet asked for. A person has to act — most likely on the client's
 *       {@code ledger:write} scope, or an address — so it is an error, every time.
 * </ul>
 *
 * <p>Registered only while {@code auth.outbox.relay.enabled} is true. {@code OutboxRelaySchedule}
 * runs {@link #deliverDue()} and {@link #queueMissing()} — {@code OutboxRelayApplicationIT} shows it
 * doing so — and {@code OutboxRelayIT} calls them directly.
 */
@Service
@ConditionalOnProperty(name = "auth.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** {@code last_error} is for a person reading the table. A whole chain of causes is not. */
    private static final int MAX_ERROR_LENGTH = 1000;

    private final OutboxRepository outbox;
    /** What writes entries, for the sweep: the envelope is decided there, once. */
    private final Outbox events;
    private final LedgerWallets ledger;
    private final Backoff backoff;
    private final int batchSize;
    private final Duration lease;
    private final Duration longestDelivery;
    /** Monotonic nanoseconds: {@link System#nanoTime()}, or a test's. */
    private final LongSupplier nanoTime;
    private final Counter delivered;
    private final Counter unavailable;
    private final Counter refused;
    private final Counter swept;

    @Autowired
    OutboxRelay(
            OutboxRepository outbox,
            Outbox events,
            LedgerWallets ledger,
            AuthProperties properties,
            MeterRegistry meters) {
        this(outbox, events, ledger, properties.outbox().relay(), meters, System::nanoTime);
    }

    /** The tests', with a monotonic clock they can move. */
    OutboxRelay(
            OutboxRepository outbox,
            Outbox events,
            LedgerWallets ledger,
            AuthProperties.Outbox.Relay settings,
            MeterRegistry meters,
            LongSupplier nanoTime) {
        this.outbox = outbox;
        this.events = events;
        this.ledger = ledger;
        this.backoff = new Backoff(settings.initialBackoff(), settings.maxBackoff());
        this.batchSize = settings.batchSize();
        this.lease = settings.lease();
        this.longestDelivery = ledger.longestOpen().multipliedBy(WalletType.values().length);
        this.nanoTime = nanoTime;
        if (batchSize < 1) {
            throw new IllegalStateException("auth.outbox.relay.batch-size must be at least 1, not " + batchSize);
        }
        if (lease.compareTo(longestDelivery) <= 0) {
            throw new IllegalStateException("auth.outbox.relay.lease is " + lease + ", but one delivery can take up "
                    + "to " + longestDelivery + ": two calls to the ledger, each perhaps after fetching a token, at "
                    + "the configured timeouts. A shorter lease would let another relay claim an entry this one is "
                    + "still delivering.");
        }
        this.delivered = Counter.builder("auth.outbox.delivered")
                .description("Outbox entries this instance delivered")
                .register(meters);
        this.unavailable = failures(meters, "unavailable");
        this.refused = failures(meters, "refused");
        this.swept = Counter.builder("auth.outbox.swept")
                .description("Accounts the sweep found without an account-created entry, and queued")
                .register(meters);
    }

    /**
     * The sweep: queue the entry of every account that is not {@code CLOSED} and has none. One
     * statement, safe on several replicas at once.
     *
     * @return how many accounts it queued; zero, normally
     */
    public int queueMissing() {
        int queued = events.queueMissingAccountCreated();
        if (queued > 0) {
            swept.increment(queued);
            log.warn("Queued {} account(s) that had no account-created entry: created by the previous version while "
                    + "this one rolled out, or by a path that did not queue its event. Their wallets follow.", queued);
        }
        return queued;
    }

    /**
     * One round: claim what is due, deliver it, record each outcome.
     *
     * <p>A failure to reach the database ends the round with an exception. What it had claimed and
     * not recorded comes due again when the lease ends.
     *
     * @return how many entries the round claimed; zero when nothing was due
     */
    public int deliverDue() {
        // Read before the claim, so the deadline falls no later than the lease the database sets.
        long claimedAt = nanoTime.getAsLong();
        List<ClaimedEntry> claimed = outbox.claimDue(batchSize, lease);
        long lastStart = claimedAt + lease.minus(longestDelivery).toNanos();
        for (int i = 0; i < claimed.size(); i++) {
            // A difference, never a comparison of the two readings: nanoTime may wrap around.
            if (nanoTime.getAsLong() - lastStart > 0) {
                log.warn("Stopped a delivery round with {} of its {} entries not started: their lease would end "
                        + "before another delivery could finish. They come due again when it does.",
                        claimed.size() - i, claimed.size());
                break;
            }
            deliver(claimed.get(i));
        }
        return claimed.size();
    }

    private void deliver(ClaimedEntry entry) {
        int attempt = entry.attempts() + 1;
        List<WalletType> closed = new ArrayList<>();
        @Nullable RuntimeException failure = null;
        try {
            // REAL first, the wallet deposits land in. A failure on BONUS repeats REAL next time,
            // which the ledger answers with the wallet it already has.
            for (WalletType type : WalletType.values()) {
                if (ledger.open(entry.accountId(), type) == Opening.CLOSED) {
                    closed.add(type);
                }
            }
        } catch (RuntimeException e) {
            failure = e;
        }
        // Outside the try: a database that cannot record the outcome is not a delivery failure,
        // and ends the round instead.
        if (failure == null) {
            recordDelivered(entry, attempt, closed);
        } else {
            recordFailed(entry, attempt, failure);
        }
    }

    private void recordDelivered(ClaimedEntry entry, int attempt, List<WalletType> closed) {
        if (!outbox.markDelivered(entry.eventId(), entry.leaseEnd())) {
            leaseLost(entry, attempt, "delivered");
            return;
        }
        delivered.increment();
        if (closed.isEmpty()) {
            log.info("Opened the REAL and BONUS wallets of account {} in the ledger (attempt {}, event {}, "
                    + "correlation {})", entry.accountId(), attempt, entry.eventId(), entry.correlationId());
        } else {
            String which = closed.size() == 1 ? closed.getFirst() + " wallet is" : "REAL and BONUS wallets are";
            log.warn("Account {}: its {} closed in the ledger, so there was nothing to open — the account had it, "
                    + "and someone closed it. Done (attempt {}, event {}, correlation {})",
                    entry.accountId(), which, attempt, entry.eventId(), entry.correlationId());
        }
    }

    private void recordFailed(ClaimedEntry entry, int attempt, RuntimeException failure) {
        Duration retryIn = backoff.after(attempt);
        String error = describe(failure);
        if (!outbox.markFailed(entry.eventId(), entry.leaseEnd(), retryIn, error)) {
            leaseLost(entry, attempt, "failed: " + error);
            return;
        }
        if (failure instanceof UpstreamUnavailableException) {
            unavailable.increment();
            log.warn("Could not open the wallets of account {} (attempt {}, event {}, correlation {}); retrying in "
                    + "{}: {}", entry.accountId(), attempt, entry.eventId(), entry.correlationId(), retryIn, error);
        } else {
            refused.increment();
            log.error("The wallets of account {} were refused (attempt {}, event {}, correlation {}). Retrying in {}, "
                    + "but this will not heal by itself: {}", entry.accountId(), attempt, entry.eventId(),
                    entry.correlationId(), retryIn, error, failure);
        }
    }

    /**
     * The outcome came back after this relay's lease on the entry was gone. Written nowhere and
     * counted nowhere: another relay has claimed the entry since, and its attempt is the one that
     * counts. Worth a warning all the same, because a delivery outlived its lease — a stalled
     * process, or a lease too short for the timeouts it has to cover.
     */
    private void leaseLost(ClaimedEntry entry, int attempt, String outcome) {
        log.warn("Lost the lease on event {} for account {} before recording its outcome ({}): it ran out, and "
                + "another relay claimed the entry, or someone changed it by hand. Recorded and counted by that "
                + "relay, not this one (attempt {}, correlation {})", entry.eventId(), entry.accountId(), outcome,
                attempt, entry.correlationId());
    }

    /**
     * Each message in the chain of causes, once: what {@code last_error} says to a person. Never a
     * token or a secret — no message this relay or its DAO builds contains one.
     */
    static String describe(Throwable failure) {
        StringBuilder text = new StringBuilder();
        @Nullable String previous = null;
        @Nullable Throwable cause = failure;
        // Ten levels at most: a chain is three or four deep, and a cycle would otherwise never end.
        for (int depth = 0; cause != null && depth < 10; cause = cause.getCause(), depth++) {
            @Nullable String message = cause.getMessage();
            String part = message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
            // A wrapper often repeats its cause's message; once is enough.
            if (previous != null && previous.contains(part)) {
                continue;
            }
            text.append(text.isEmpty() ? "" : ": ").append(part);
            previous = part;
        }
        return text.length() <= MAX_ERROR_LENGTH ? text.toString() : text.substring(0, MAX_ERROR_LENGTH - 1) + "…";
    }

    private static Counter failures(MeterRegistry meters, String reason) {
        return Counter.builder("auth.outbox.failed")
                .description("Outbox delivery attempts that failed and will be retried")
                .tag("reason", reason)
                .register(meters);
    }
}
