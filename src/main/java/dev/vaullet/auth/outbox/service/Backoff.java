package dev.vaullet.auth.outbox.service;

import java.time.Duration;

/**
 * How long an entry waits after a failed attempt: the first delay, doubled with each further
 * failure, and never more than the cap.
 *
 * <p>An entry is never given up on. ADR-007 §10 says financial events are never dropped, and an
 * account without its wallets is a customer who cannot be paid; so past the cap it is simply tried
 * again every cap, and the backlog gauges are what say that something is wrong. With the defaults —
 * 10 seconds doubling to 15 minutes — an entry that has been failing for a day has been tried about
 * a hundred times, and is delivered within a quarter of an hour of the ledger coming back.
 *
 * <p>No jitter. Entries that fail together do come due together, but a round claims at most
 * {@code batch-size} of them, and the poll interval spaces the rounds — the claim is the rate limit.
 */
final class Backoff {

    private final Duration first;
    private final Duration cap;

    Backoff(Duration first, Duration cap) {
        if (first.isNegative() || first.isZero()) {
            throw new IllegalArgumentException("The first backoff must be positive, not " + first);
        }
        if (cap.compareTo(first) < 0) {
            throw new IllegalArgumentException("The backoff cap " + cap + " is shorter than the first backoff " + first);
        }
        this.first = first;
        this.cap = cap;
    }

    /**
     * The wait after the {@code attempts}-th failed attempt, counting from 1.
     *
     * <p>Doubles by repeated multiplication and stops at the cap, rather than computing
     * {@code first × 2^(attempts − 1)}: attempts keep counting for as long as an entry keeps failing,
     * and that power overflows long before the count does.
     */
    Duration after(int attempts) {
        if (attempts < 1) {
            throw new IllegalArgumentException("Attempts count from 1, not " + attempts);
        }
        Duration wait = first;
        for (int doubled = 1; doubled < attempts && wait.compareTo(cap) < 0; doubled++) {
            wait = wait.multipliedBy(2);
        }
        return wait.compareTo(cap) > 0 ? cap : wait;
    }

    @Override
    public String toString() {
        return "Backoff[first=" + first + ", cap=" + cap + "]";
    }
}
