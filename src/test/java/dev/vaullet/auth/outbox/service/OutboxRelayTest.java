package dev.vaullet.auth.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vaullet.auth.config.AuthProperties;
import dev.vaullet.auth.outbox.dao.LedgerWallets;
import dev.vaullet.auth.outbox.dao.LedgerWallets.Opening;
import dev.vaullet.auth.outbox.dao.LedgerWallets.WalletType;
import dev.vaullet.auth.outbox.dao.OutboxRepository;
import dev.vaullet.auth.outbox.dao.OutboxRepository.ClaimedEntry;
import dev.vaullet.common.error.exception.UpstreamUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.ConnectException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
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
 * What the relay does with each answer, without a database or a ledger.
 *
 * <p>Both collaborators are mocked and the relay is real. Asserted here: which outcome each answer
 * records, under which lease, the backoff the attempt count chooses, what a failure is counted as,
 * what a lost lease counts (nothing), and that a round stops before a lease could end under a
 * delivery. {@code OutboxRelayIT} runs the same relay
 * against PostgreSQL and stand-ins on the wire; {@code LedgerWalletsTest} pins how an HTTP answer
 * becomes an {@link Opening} or an exception.
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final UUID ACCOUNT = UUID.fromString("dddddddd-0000-0000-0000-000000000001");
    private static final Duration LEASE = Duration.ofMinutes(2);
    /** One open, as LedgerWallets would report it with 5-second timeouts. */
    private static final Duration LONGEST_OPEN = Duration.ofSeconds(20);
    /** What the claim set next_attempt_at to, and every outcome must show. */
    private static final Instant LEASE_END = Instant.parse("2026-10-14T10:02:00.123456Z");

    @Mock
    private OutboxRepository outbox;

    @Mock
    private Outbox events;

    @Mock
    private LedgerWallets ledger;

    /**
     * The relay's monotonic clock, moved by hand. It starts just short of where a long wraps around,
     * as System.nanoTime may, so the round in {@code stops_before_the_lease_runs_out} crosses it.
     */
    private final AtomicLong nanos = new AtomicLong(Long.MAX_VALUE - Duration.ofSeconds(30).toNanos());
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        when(ledger.longestOpen()).thenReturn(LONGEST_OPEN);
        relay = new OutboxRelay(outbox, events, ledger, settings(LEASE, 20), meters, nanos::get);
    }

    private static AuthProperties.Outbox.Relay settings(Duration lease, int batchSize) {
        return new AuthProperties.Outbox.Relay(
                true, Duration.ofSeconds(5), batchSize, lease, Duration.ofSeconds(10), Duration.ofMinutes(15),
                Duration.ofMinutes(10));
    }

    private static ClaimedEntry entry(int attempts) {
        return entry(ACCOUNT, attempts);
    }

    private static ClaimedEntry entry(UUID accountId, int attempts) {
        return new ClaimedEntry(UUID.randomUUID(), "identity.account-created", accountId, "4bf92f3577b34da6a3ce929d0e0e4736",
                attempts, Instant.parse("2026-10-14T09:59:00Z"), LEASE_END);
    }

    private void claims(ClaimedEntry... entries) {
        when(outbox.claimDue(20, LEASE)).thenReturn(List.of(entries));
    }

    private double counted(String name, String... tags) {
        return meters.get(name).tags(tags).counter().count();
    }

    @Nested
    @DisplayName("an entry is delivered")
    class Delivered {

        @Test
        @DisplayName("when both wallets open, REAL first")
        void both_open() {
            ClaimedEntry claimed = entry(0);
            claims(claimed);
            when(outbox.markDelivered(claimed.eventId(), LEASE_END)).thenReturn(true);
            when(ledger.open(ACCOUNT, WalletType.REAL)).thenReturn(Opening.OPENED);
            when(ledger.open(ACCOUNT, WalletType.BONUS)).thenReturn(Opening.OPENED);

            assertThat(relay.deliverDue()).isEqualTo(1);

            InOrder order = inOrder(ledger, outbox);
            order.verify(ledger).open(ACCOUNT, WalletType.REAL);
            order.verify(ledger).open(ACCOUNT, WalletType.BONUS);
            order.verify(outbox).markDelivered(claimed.eventId(), LEASE_END);
            verify(outbox, never()).markFailed(any(), any(), any(), anyString());
            assertThat(counted("auth.outbox.delivered")).isEqualTo(1);
        }

        @Test
        @DisplayName("when a wallet was closed: the account had it, and closing is terminal")
        void closed_counts_as_done() {
            ClaimedEntry claimed = entry(0);
            claims(claimed);
            when(outbox.markDelivered(claimed.eventId(), LEASE_END)).thenReturn(true);
            when(ledger.open(ACCOUNT, WalletType.REAL)).thenReturn(Opening.CLOSED);
            when(ledger.open(ACCOUNT, WalletType.BONUS)).thenReturn(Opening.OPENED);

            relay.deliverDue();

            verify(outbox).markDelivered(claimed.eventId(), LEASE_END);
            verify(outbox, never()).markFailed(any(), any(), any(), anyString());
        }
    }

    @Nested
    @DisplayName("an outcome that comes back after the lease was lost: another relay's now")
    class LeaseLost {

        @Test
        @DisplayName("a delivery is not counted, nor recorded as a failure instead")
        void after_delivering() {
            ClaimedEntry claimed = entry(0);
            claims(claimed);
            when(outbox.markDelivered(claimed.eventId(), LEASE_END)).thenReturn(false);
            when(ledger.open(any(), any())).thenReturn(Opening.OPENED);

            assertThat(relay.deliverDue()).isEqualTo(1);

            assertThat(counted("auth.outbox.delivered")).isZero();
            verify(outbox, never()).markFailed(any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("a failure counts nothing either: the attempt is the next claim's to count")
        void after_failing() {
            ClaimedEntry claimed = entry(0);
            claims(claimed);
            when(ledger.open(any(), any())).thenThrow(new UpstreamUnavailableException("ledger",
                    new IllegalStateException("down")));
            when(outbox.markFailed(eq(claimed.eventId()), eq(LEASE_END), any(), anyString())).thenReturn(false);

            relay.deliverDue();

            assertThat(counted("auth.outbox.failed", "reason", "unavailable")).isZero();
            assertThat(counted("auth.outbox.failed", "reason", "refused")).isZero();
            verify(outbox, never()).markDelivered(any(), any());
        }
    }

    @Nested
    @DisplayName("an entry fails, and is due again after the backoff")
    class Failed {

        @Test
        @DisplayName("the ledger not answering is 'unavailable'; nothing after the failing call is tried")
        void unavailable() {
            ClaimedEntry claimed = entry(0);
            claims(claimed);
            when(ledger.open(ACCOUNT, WalletType.REAL)).thenThrow(new UpstreamUnavailableException("ledger",
                    new IllegalStateException("HTTP 503 ACCOUNT_BUSY from the ledger on POST /v1/wallets REAL")));
            when(outbox.markFailed(eq(claimed.eventId()), eq(LEASE_END), any(), anyString())).thenReturn(true);

            relay.deliverDue();

            verify(ledger, never()).open(ACCOUNT, WalletType.BONUS);
            verify(outbox).markFailed(claimed.eventId(), LEASE_END, Duration.ofSeconds(10),
                    "Upstream 'ledger' is unavailable: HTTP 503 ACCOUNT_BUSY from the ledger on POST /v1/wallets REAL");
            verify(outbox, never()).markDelivered(any(), any());
            assertThat(counted("auth.outbox.failed", "reason", "unavailable")).isEqualTo(1);
            assertThat(counted("auth.outbox.failed", "reason", "refused")).isZero();
        }

        @Test
        @DisplayName("a refusal is 'refused': retried all the same, and an error every time")
        void refused() {
            ClaimedEntry claimed = entry(0);
            claims(claimed);
            when(ledger.open(ACCOUNT, WalletType.REAL)).thenReturn(Opening.OPENED);
            when(ledger.open(ACCOUNT, WalletType.BONUS))
                    .thenThrow(new IllegalStateException("The ledger refused POST /v1/wallets BONUS with HTTP 403"));
            when(outbox.markFailed(eq(claimed.eventId()), eq(LEASE_END), any(), anyString())).thenReturn(true);

            relay.deliverDue();

            verify(outbox).markFailed(claimed.eventId(), LEASE_END, Duration.ofSeconds(10),
                    "The ledger refused POST /v1/wallets BONUS with HTTP 403");
            assertThat(counted("auth.outbox.failed", "reason", "refused")).isEqualTo(1);
        }

        @Test
        @DisplayName("the attempt count chooses the backoff: a fourth failure waits 80 seconds")
        void backoff_follows_attempts() {
            ClaimedEntry claimed = entry(3);
            claims(claimed);
            when(ledger.open(any(), any())).thenThrow(new UpstreamUnavailableException("ledger", new IllegalStateException("down")));
            when(outbox.markFailed(eq(claimed.eventId()), eq(LEASE_END), any(), anyString())).thenReturn(true);

            relay.deliverDue();

            verify(outbox).markFailed(eq(claimed.eventId()), eq(LEASE_END), eq(Duration.ofSeconds(80)), anyString());
        }

        @Test
        @DisplayName("one entry failing does not stop the others in the round")
        void others_carry_on() {
            UUID other = UUID.randomUUID();
            ClaimedEntry failing = entry(0);
            ClaimedEntry fine = entry(other, 0);
            claims(failing, fine);
            when(ledger.open(eq(ACCOUNT), any())).thenThrow(new IllegalStateException("Unexpected HTTP 400"));
            when(ledger.open(eq(other), any())).thenReturn(Opening.OPENED);
            when(outbox.markFailed(eq(failing.eventId()), eq(LEASE_END), any(), anyString())).thenReturn(true);
            when(outbox.markDelivered(fine.eventId(), LEASE_END)).thenReturn(true);

            assertThat(relay.deliverDue()).isEqualTo(2);

            verify(outbox).markDelivered(fine.eventId(), LEASE_END);
        }

        @Test
        @DisplayName("a database that cannot record the outcome ends the round, rather than passing for a failed delivery")
        void database_failure_ends_the_round() {
            ClaimedEntry first = entry(0);
            ClaimedEntry second = entry(UUID.randomUUID(), 0);
            claims(first, second);
            when(ledger.open(any(), any())).thenReturn(Opening.OPENED);
            when(outbox.markDelivered(first.eventId(), LEASE_END)).thenThrow(new IllegalStateException("database gone"));

            assertThatThrownBy(() -> relay.deliverDue()).hasMessage("database gone");

            verify(outbox, never()).markFailed(any(), any(), any(), anyString());
            verify(ledger, never()).open(eq(second.accountId()), any());
        }
    }

    @Nested
    @DisplayName("the lease")
    class Lease {

        @Test
        @DisplayName("a round stops starting deliveries before the lease could end under one")
        void stops_before_the_lease_runs_out() {
            ClaimedEntry first = entry(0);
            ClaimedEntry second = entry(UUID.randomUUID(), 0);
            ClaimedEntry third = entry(UUID.randomUUID(), 0);
            claims(first, second, third);
            // Each open takes 25 seconds: a stalled ledger, just inside the timeouts.
            when(ledger.open(any(), any())).thenAnswer(call -> {
                nanos.addAndGet(Duration.ofSeconds(25).toNanos());
                return Opening.OPENED;
            });
            when(outbox.markDelivered(any(), any())).thenReturn(true);

            assertThat(relay.deliverDue()).isEqualTo(3);

            // A delivery can take 40s, so none may start after 80s into a 2-minute lease. The first
            // ends at 50s, the second at 100s; the third is left for its lease to return it.
            verify(outbox).markDelivered(first.eventId(), LEASE_END);
            verify(outbox).markDelivered(second.eventId(), LEASE_END);
            verify(outbox, never()).markDelivered(third.eventId(), LEASE_END);
            verify(ledger, never()).open(eq(third.accountId()), any());
        }

        @Test
        @DisplayName("the relay refuses to start with a lease too short for one delivery")
        void refuses_a_short_lease() {
            assertThatThrownBy(() -> new OutboxRelay(outbox, events, ledger, settings(Duration.ofSeconds(40), 20), meters, nanos::get))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("auth.outbox.relay.lease is PT40S")
                    .hasMessageContaining("PT40S");
        }

        @Test
        @DisplayName("...and a batch of nothing")
        void refuses_an_empty_batch() {
            assertThatThrownBy(() -> new OutboxRelay(outbox, events, ledger, settings(LEASE, 0), meters, nanos::get))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("batch-size");
        }

        @Test
        @DisplayName("claims with the configured batch size and lease")
        void claims_as_configured() {
            when(outbox.claimDue(anyInt(), any())).thenReturn(List.of());

            assertThat(relay.deliverDue()).isZero();

            ArgumentCaptor<Duration> lease = ArgumentCaptor.forClass(Duration.class);
            verify(outbox).claimDue(eq(20), lease.capture());
            assertThat(lease.getValue()).isEqualTo(LEASE);
        }
    }

    @Nested
    @DisplayName("the sweep")
    class Sweep {

        @Test
        @DisplayName("queues what Outbox finds missing, and counts it")
        void counts_what_it_queued() {
            when(events.queueMissingAccountCreated()).thenReturn(3);

            assertThat(relay.queueMissing()).isEqualTo(3);

            assertThat(meters.get("auth.outbox.swept").counter().count()).isEqualTo(3);
            // A sweep is not a delivery round: it claims and calls nothing.
            verify(outbox, never()).claimDue(anyInt(), any());
            verify(ledger, never()).open(any(), any());
        }

        @Test
        @DisplayName("normally finds nothing, and says nothing")
        void finds_nothing() {
            when(events.queueMissingAccountCreated()).thenReturn(0);

            assertThat(relay.queueMissing()).isZero();

            assertThat(meters.get("auth.outbox.swept").counter().count()).isZero();
        }
    }

    @Nested
    @DisplayName("last_error")
    class Describe {

        @Test
        @DisplayName("says each message in the chain once")
        void each_message_once() {
            RuntimeException failure = new UpstreamUnavailableException("ledger",
                    new IllegalStateException("I/O error on POST request", new ConnectException()));

            assertThat(OutboxRelay.describe(failure))
                    .isEqualTo("Upstream 'ledger' is unavailable: I/O error on POST request: ConnectException");
        }

        @Test
        @DisplayName("leaves out a cause whose message the wrapper already repeats")
        void no_repeats() {
            IllegalStateException cause = new IllegalStateException("Connection refused");
            RuntimeException wrapper = new RuntimeException("I/O error: Connection refused", cause);

            assertThat(OutboxRelay.describe(wrapper)).isEqualTo("I/O error: Connection refused");
        }

        @Test
        @DisplayName("is cut at a thousand characters")
        void bounded() {
            assertThat(OutboxRelay.describe(new IllegalStateException("x".repeat(5000)))).hasSize(1000).endsWith("…");
        }
    }
}
