package dev.vaullet.auth.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vaullet.auth.account.dao.AccountRepository;
import dev.vaullet.auth.account.dao.AccountRepository.AccountRow;
import dev.vaullet.common.test.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writing the outbox: only inside the transaction that creates the account, and as ADR-007's
 * envelope.
 *
 * <p>Through the real {@link Outbox} bean, because the rule being tested lives in its transaction
 * proxy — {@code Propagation.MANDATORY} — which a unit test does not build. {@code AccountApiIT} and
 * {@code EndUserApiIT} show the same from the two endpoints that create accounts.
 */
@IntegrationTest
class OutboxIT {

    @Autowired
    private Outbox outbox;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Tracer tracer;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private ObjectProvider<BuildProperties> build;

    @BeforeEach
    void clean() {
        // Accounts too: the sweep reads every account, and other tests leave theirs behind.
        jdbc.execute("TRUNCATE identity_schema.outbox, identity_schema.accounts");
    }

    private AccountRow anAccount() {
        UUID id = UUID.randomUUID();
        return accounts.insert(id, null, "acme-" + id, "ACTIVE");
    }

    private int entries() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM identity_schema.outbox", Integer.class);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("refuses to write outside a transaction, before writing anything")
    void needs_a_transaction() {
        AccountRow account = anAccount();

        // On its own the entry would commit whether or not the account did: the gap the outbox closes.
        assertThatThrownBy(() -> outbox.accountCreated(account.getAccountId(), account.getCreatedAt()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(entries()).isZero();
    }

    @Test
    @DisplayName("rolls back with the account: a creation that fails leaves neither row")
    void rolls_back_with_the_account() {
        UUID id = UUID.randomUUID();

        transactions.executeWithoutResult(status -> {
            AccountRow account = accounts.insert(id, null, "acme-" + id, "ACTIVE");
            outbox.accountCreated(account.getAccountId(), account.getCreatedAt());
            // Whatever comes next in the creation fails.
            status.setRollbackOnly();
        });

        assertThat(accounts.findById(id)).isEmpty();
        assertThat(entries()).isZero();
    }

    @Test
    @DisplayName("commits with the account, as the ADR-007 envelope")
    void the_envelope() {
        UUID id = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            AccountRow account = accounts.insert(id, null, "acme-" + id, "ACTIVE");
            outbox.accountCreated(account.getAccountId(), account.getCreatedAt());
        });

        Map<String, Object> entry = jdbc.queryForMap("""
                SELECT o.event_type, o.schema_version, o.producer_service, o.producer_version, o.correlation_id,
                       o.causation_id, o.payload::text AS payload, o.attempts, o.delivered_at, o.last_error,
                       o.occurred_at = a.created_at AS occurred_at_creation,
                       o.queued_at = a.created_at AS queued_in_the_same_transaction
                  FROM identity_schema.outbox o JOIN identity_schema.accounts a USING (account_id)
                 WHERE o.account_id = ?""", id);

        assertThat(entry)
                .containsEntry("event_type", "identity.account-created")
                .containsEntry("schema_version", "1.0.0")
                .containsEntry("producer_service", "auth-service")
                .containsEntry("payload", "{}")
                .containsEntry("attempts", 0)
                .containsEntry("occurred_at_creation", true)
                // now() is the transaction's start, so one transaction writes one timestamp.
                .containsEntry("queued_in_the_same_transaction", true);
        // The build's own version, from the build-info Maven writes; none when it did not.
        BuildProperties info = build.getIfAvailable();
        assertThat(entry.get("producer_version")).isEqualTo(info == null ? null : info.getVersion());
        // No request, so no trace to correlate with; and no event causes an account.
        assertThat(entry.get("correlation_id")).isNull();
        assertThat(entry.get("causation_id")).isNull();
        assertThat(entry.get("delivered_at")).isNull();
        assertThat(entry.get("last_error")).isNull();
    }

    @Test
    @DisplayName("carries the trace id of the request that created the account as its correlation id")
    void correlation_is_the_trace() {
        UUID id = UUID.randomUUID();
        Span request = tracer.nextSpan().name("POST /v1/accounts").start();
        Tracer.SpanInScope scope = tracer.withSpan(request);
        try {
            transactions.executeWithoutResult(status -> {
                AccountRow account = accounts.insert(id, null, "acme-" + id, "ACTIVE");
                outbox.accountCreated(account.getAccountId(), account.getCreatedAt());
            });
        } finally {
            scope.close();
            request.end();
        }

        assertThat(jdbc.queryForObject("SELECT correlation_id FROM identity_schema.outbox WHERE account_id = ?",
                String.class, id)).isEqualTo(request.context().traceId());
    }

    @Test
    @DisplayName("the sweep writes the same envelope, as this build, for the accounts that have none")
    void the_sweep() {
        AccountRow without = anAccount();
        UUID with = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            AccountRow account = accounts.insert(with, null, "acme-" + with, "ACTIVE");
            outbox.accountCreated(account.getAccountId(), account.getCreatedAt());
        });

        // Outside any transaction, unlike accountCreated: a sweep is no part of a creation.
        assertThat(outbox.queueMissingAccountCreated()).isEqualTo(1);

        Map<String, Object> entry = jdbc.queryForMap("""
                SELECT o.event_type, o.schema_version, o.producer_service, o.producer_version, o.correlation_id,
                       o.payload::text AS payload, o.occurred_at = a.created_at AS occurred_at_creation
                  FROM identity_schema.outbox o JOIN identity_schema.accounts a USING (account_id)
                 WHERE o.account_id = ?""", without.getAccountId());
        assertThat(entry)
                .containsEntry("event_type", "identity.account-created")
                .containsEntry("schema_version", "1.0.0")
                .containsEntry("producer_service", "auth-service")
                .containsEntry("payload", "{}")
                .containsEntry("occurred_at_creation", true);
        BuildProperties info = build.getIfAvailable();
        assertThat(entry.get("producer_version")).isEqualTo(info == null ? null : info.getVersion());
        assertThat(entry.get("correlation_id")).isNull();
        assertThat(entries()).isEqualTo(2);
    }

    @Test
    @DisplayName("reports the backlog: how many entries wait, and how long the oldest has")
    void backlog_gauges() {
        assertThat(meters.get("auth.outbox.pending").gauge().value()).isZero();
        assertThat(meters.get("auth.outbox.oldest.pending").timeGauge().value(TimeUnit.SECONDS)).isZero();

        for (int i = 0; i < 2; i++) {
            transactions.executeWithoutResult(status -> {
                AccountRow account = anAccount();
                outbox.accountCreated(account.getAccountId(), account.getCreatedAt());
            });
        }
        jdbc.update("UPDATE identity_schema.outbox SET queued_at = now() - interval '10 minutes'");

        assertThat(meters.get("auth.outbox.pending").gauge().value()).isEqualTo(2);
        assertThat(meters.get("auth.outbox.oldest.pending").timeGauge().value(TimeUnit.SECONDS))
                .isBetween(595.0, 660.0);
    }
}
