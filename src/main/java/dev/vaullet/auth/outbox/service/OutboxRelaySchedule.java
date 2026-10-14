package dev.vaullet.auth.outbox.service;

import dev.vaullet.auth.config.AuthProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Runs the relay's two jobs: {@link OutboxRelay#deliverDue()} every
 * {@code auth.outbox.relay.poll-interval}, and {@link OutboxRelay#queueMissing()} at start and then
 * every {@code sweep-interval}. Both measured from the end of one run to the start of the next, so
 * a slow run is never overlapped by the next one on the same instance.
 *
 * <p>Scheduling exists only with the relay switched on: {@code @EnableScheduling} is here, on a
 * class that is not registered otherwise, rather than on the application. Off — in the
 * {@code local}, {@code test} and {@code migrate} profiles — nothing polls the database.
 *
 * <p>Registered in code rather than with {@code @Scheduled}, so the intervals are the bound,
 * validated {@link Duration}s from {@link AuthProperties} instead of strings an annotation parses
 * its own way.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "auth.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
class OutboxRelaySchedule implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelaySchedule.class);

    private final OutboxRelay relay;
    private final Duration interval;
    private final Duration sweepInterval;

    OutboxRelaySchedule(OutboxRelay relay, AuthProperties properties) {
        this.relay = relay;
        this.interval = positive(properties.outbox().relay().pollInterval(), "poll-interval");
        this.sweepInterval = positive(properties.outbox().relay().sweepInterval(), "sweep-interval");
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar tasks) {
        tasks.addFixedDelayTask(this::round, interval);
        // No initial delay: the first sweep runs as the instance starts, which is when the accounts
        // the previous version created during the rollout are waiting.
        tasks.addFixedDelayTask(this::sweep, sweepInterval);
        log.info("Outbox relay on: a round every {}, a sweep every {}", interval, sweepInterval);
    }

    /**
     * One round, and a failed one is a warning rather than the scheduler's stack trace: the next
     * round is only {@code poll-interval} away, and whatever this one claimed comes due again when
     * its lease ends. A database that stays unreachable says so once a round.
     */
    private void round() {
        try {
            relay.deliverDue();
        } catch (RuntimeException e) {
            log.warn("An outbox relay round failed; the next starts in {}: {}", interval,
                    OutboxRelay.describe(e));
        }
    }

    /** One sweep. A failed one is retried at the next, and costs nothing in between. */
    private void sweep() {
        try {
            relay.queueMissing();
        } catch (RuntimeException e) {
            log.warn("An outbox sweep failed; the next starts in {}: {}", sweepInterval, OutboxRelay.describe(e));
        }
    }

    private static Duration positive(Duration value, String property) {
        if (value.isNegative() || value.isZero()) {
            throw new IllegalStateException("auth.outbox.relay." + property + " must be positive, not " + value);
        }
        return value;
    }
}
