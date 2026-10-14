package dev.vaullet.auth.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** How long an entry waits after each failed attempt — with the defaults, 10 seconds doubling to 15 minutes. */
class BackoffTest {

    private final Backoff defaults = new Backoff(Duration.ofSeconds(10), Duration.ofMinutes(15));

    @ParameterizedTest(name = "after attempt {0}: {1}s")
    @CsvSource({
        "1, 10",
        "2, 20",
        "3, 40",
        "4, 80",
        "5, 160",
        "6, 320",
        "7, 640",
        // 1280 would be next: the cap, and from here on always the cap.
        "8, 900",
        "9, 900",
        "100, 900",
    })
    @DisplayName("doubles from the first delay, and stops at the cap")
    void doubles_then_caps(int attempts, long seconds) {
        assertThat(defaults.after(attempts)).isEqualTo(Duration.ofSeconds(seconds));
    }

    @Test
    @DisplayName("an entry that keeps failing for ever never overflows the arithmetic")
    void never_overflows() {
        // 10s × 2^(n−1) overflows a long after some sixty doublings; attempts keep counting far
        // longer than that while the ledger stays away.
        assertThat(defaults.after(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("a cap equal to the first delay makes every wait the same")
    void flat_when_cap_equals_first() {
        Backoff flat = new Backoff(Duration.ofSeconds(30), Duration.ofSeconds(30));

        assertThat(flat.after(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(flat.after(50)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("a cap that is not a doubling of the first delay is still the most it waits")
    void cap_between_doublings() {
        Backoff odd = new Backoff(Duration.ofSeconds(10), Duration.ofSeconds(25));

        assertThat(odd.after(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(odd.after(3)).isEqualTo(Duration.ofSeconds(25));
    }

    @Test
    @DisplayName("attempts count from 1")
    void attempts_count_from_one() {
        assertThatThrownBy(() -> defaults.after(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refuses a first delay that is not positive, or a cap below it")
    void refuses_nonsense() {
        assertThatThrownBy(() -> new Backoff(Duration.ZERO, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Backoff(Duration.ofMinutes(2), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shorter than the first backoff");
    }
}
